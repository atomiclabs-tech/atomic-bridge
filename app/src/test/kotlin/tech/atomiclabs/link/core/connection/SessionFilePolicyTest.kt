package tech.atomiclabs.link.core.connection

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test
import tech.atomiclabs.link.core.command.CommandRouter
import tech.atomiclabs.link.core.fakes.*
import tech.atomiclabs.link.core.port.*
import tech.atomiclabs.link.core.status.StatusReporter

@OptIn(ExperimentalCoroutinesApi::class)
class SessionFilePolicyTest {
    @Test fun saveThenLoadPreservesEveryFieldAndDoesNotStorePlaintext() = runTest {
        val files = MemorySessionFiles()
        val cipher = PolicyCipher()
        val logger = FakeLogger()
        val policy = SessionFilePolicy(files, cipher, FakeClock(testScheduler), logger)
        val session = originalSession().copy(expiresAtEpochSeconds = 500, grants = mapOf("bridge" to null, "other" to 400), savedAtEpochMs = 123)
        assertTrue(policy.save(session) is PortResult.Success)
        assertFalse(files.bytes!!.decodeToString().contains(session.sessionToken))
        val plain = (cipher.decrypt(files.bytes!!) as PortResult.Success).value.decodeToString()
        assertEquals(setOf("session_token", "refresh_token", "expires_at", "grants", "saved_at"),
            Json.parseToJsonElement(plain).jsonObject.keys)
        assertEquals(ReadValue.Present(session), policy.read())
        assertFalse(files.preserve.single())
        assertTrue(logger.entries.isEmpty())
    }

    @Test fun absenceIsOneCompletedOutcomeWithoutCipherOrWrite() = runTest {
        val files = MemorySessionFiles()
        val cipher = PolicyCipher()
        val policy = SessionFilePolicy(files, cipher, FakeClock(testScheduler), FakeLogger())
        assertEquals(ReadValue.Absent, policy.read())
        assertEquals(1, files.reads)
        assertEquals(0, cipher.decrypts)
        assertTrue(files.preserve.isEmpty())
    }

    @Test fun throwingCipherGetsExactlyThreeOneSecondRetriesAndNeverChangesTheFile() = runTest {
        val files = MemorySessionFiles()
        val cipher = PolicyCipher()
        val logger = FakeLogger()
        val policy = SessionFilePolicy(files, cipher, FakeClock(testScheduler), logger)
        policy.save(originalSession())
        val before = files.bytes!!.copyOf()
        val cause = IllegalStateException("private-decrypt", SecurityException("private-nested"))
        val times = mutableListOf<Long>()
        cipher.onDecrypt = { times += currentTime; throw cause }
        val result = policy.read() as ReadValue.Unreadable
        assertEquals(listOf(0L, 1_000L, 2_000L, 3_000L), times)
        assertEquals(4, cipher.decrypts)
        assertArrayEquals(before, files.bytes)
        assertTrue(generateSequence(result.failure.cause) { it.cause }.any { it.stackTrace.contentEquals(cause.stackTrace) })
        assertNotNull(result.failure.cause.cause)
        assertEquals(4, logger.entries.count { it.level == "error" })
        assertSafe(logger, result.failure.cause, "private-decrypt", "private-nested", originalSession().sessionToken)
    }

    @Test fun transientDecryptFailureThenSuccessReturnsTheSessionAfterOneRetry() = runTest {
        val files = MemorySessionFiles()
        val cipher = PolicyCipher()
        val logger = FakeLogger()
        val policy = SessionFilePolicy(files, cipher, FakeClock(testScheduler), logger)
        policy.save(originalSession())
        cipher.onDecrypt = { if (cipher.decrypts == 1) throw IllegalStateException("private-transient") }
        assertEquals(ReadValue.Present(originalSession()), policy.read())
        assertEquals(1_000L, currentTime)
        assertEquals(2, cipher.decrypts)
        assertEquals(1, logger.entries.size)
        assertSafe(logger, null, "private-transient")
    }

    @Test fun rawReadAndMalformedContentAreUnreadableRatherThanAbsentOrDeleted() = runTest {
        for (malformed in listOf(false, true)) {
            val files = MemorySessionFiles().apply { bytes = byteArrayOf(1, 2, 3) }
            val before = files.bytes!!.copyOf()
            val logger = FakeLogger()
            val cipher = PolicyCipher()
            if (malformed) cipher.decrypted = "{private-malformed".encodeToByteArray()
            else files.onRead = { throw IllegalStateException("private-read") }
            val policy = SessionFilePolicy(files, cipher, FakeClock(testScheduler), logger)
            val result = policy.read() as ReadValue.Unreadable
            assertEquals(4, files.reads)
            assertEquals(if (malformed) FailureKind.INVALID_DATA else FailureKind.IO, result.failure.kind)
            assertArrayEquals(before, files.bytes)
            assertTrue(files.preserve.isEmpty())
            assertSafe(logger, result.failure.cause, "private-read", "private-malformed")
        }
    }

    @Test fun encryptAndReplaceFailuresAreCheckedOnceAndLeaveOldBytesIntact() = runTest {
        for (encrypt in listOf(true, false)) {
            val files = MemorySessionFiles()
            val cipher = PolicyCipher()
            val logger = FakeLogger()
            val policy = SessionFilePolicy(files, cipher, FakeClock(testScheduler), logger)
            policy.save(originalSession())
            val before = files.bytes!!.copyOf()
            val failure = PortResult.Failure(PortFailure(FailureKind.IO, IllegalStateException("private-save")))
            if (encrypt) cipher.encryptFailure = failure else files.replaceResult = failure
            val result = policy.save(originalSession().copy(sessionToken = "replacement-fixture")) as PortResult.Failure
            assertArrayEquals(before, files.bytes)
            assertEquals(if (encrypt) 1 else 2, files.preserve.size)
            assertSafe(logger, result.failure.cause, "private-save", "replacement-fixture")
        }
    }

    @Test fun directSaveClassifiesUnreadableOldBytesBeforeReplacingThem() = runTest {
        val files = MemorySessionFiles().apply { bytes = byteArrayOf(1) }
        val cipher = PolicyCipher().apply { onDecrypt = { throw IllegalStateException("private-old") } }
        val logger = FakeLogger()
        val policy = SessionFilePolicy(files, cipher, FakeClock(testScheduler), logger)
        assertTrue(policy.save(originalSession()) is PortResult.Success)
        assertTrue(files.preserve.single())
        assertEquals(1, cipher.decrypts)
        assertSafe(logger, null, "private-old")
    }

    @Test fun stalledReadsAndSavesHaveFiniteBudgetsWithoutExtraWriteRetries() = runTest {
        val files = MemorySessionFiles().apply { onRead = { awaitCancellation() } }
        val logger = FakeLogger()
        val policy = SessionFilePolicy(files, PolicyCipher(), FakeClock(testScheduler), logger)
        assertEquals(FailureKind.TIMED_OUT, (policy.read() as ReadValue.Unreadable).failure.kind)
        assertEquals(35_000L, currentTime)
        assertEquals(4, files.reads)
        assertTrue(files.preserve.isEmpty())
        val cipher = PolicyCipher().apply { onEncrypt = { awaitCancellation() } }
        val save = SessionFilePolicy(MemorySessionFiles(), cipher, FakeClock(testScheduler), logger)
        assertEquals(FailureKind.TIMED_OUT, (save.save(originalSession()) as PortResult.Failure).failure.kind)
        assertEquals(43_000L, currentTime)
    }

    @Test fun cancellingALoadDoesNotRetryOrChangeTheFile() = runTest {
        val files = MemorySessionFiles().apply { bytes = byteArrayOf(1); onRead = { awaitCancellation() } }
        val logger = FakeLogger()
        val policy = SessionFilePolicy(files, PolicyCipher(), FakeClock(testScheduler), logger)
        val job = backgroundScope.launch { policy.read() }
        runCurrent(); job.cancelAndJoin(); advanceTimeBy(10_000); runCurrent()
        assertEquals(1, files.reads)
        assertArrayEquals(byteArrayOf(1), files.bytes)
        assertTrue(files.preserve.isEmpty() && logger.entries.isEmpty())
    }

    @Test fun supervisorConsumesTheActualRetryingPolicyOnceWithoutMultiplyingAttempts() = runTest {
        val files = MemorySessionFiles().apply { bytes = byteArrayOf(1) }
        val times = mutableListOf<Long>()
        val cipher = PolicyCipher().apply { onDecrypt = { times += currentTime; throw IllegalStateException("private-joined") } }
        val logger = FakeLogger()
        val clock = FakeClock(testScheduler)
        val policy = SessionFilePolicy(files, cipher, clock, logger)
        val identity = DeviceIdentity { PortResult.Success(description()) }
        val settings = FakeSettingsStore().apply {
            url = ReadValue.Present("wss://relay.example")
            id = ReadValue.Present("11111111-2222-3333-4444-555555555555")
        }
        val network = object : NetworkMonitor {
            override val changes = emptyFlow<PortResult<Boolean>>()
            override suspend fun isAvailable() = PortResult.Success(true)
        }
        val reporter = StatusReporter(identity, DeviceStateSource { PortResult.Success(DeviceSnapshot(50, true, null, null)) },
            PermissionState { PortResult.Success(permissions()) }, clock, logger) { "id" }
        val supervisor = ConnectionSupervisor(RelaySocketFactory { error("A socket must not open.") }, network, policy,
            settings, identity, clock, CommandRouter(emptyList(), FakeUiTree(), FakeDisplayGate(), logger), reporter,
            logger, { "11111111-2222-3333-4444-555555555555" })
        backgroundScope.launch { supervisor.run() }
        runCurrent(); advanceTimeBy(3_000); runCurrent()
        assertEquals(listOf(0L, 1_000L, 2_000L, 3_000L), times)
        assertEquals(4, files.reads)
        assertEquals(ConnectionPhase.SESSION_UNREADABLE, supervisor.states.value.phase)
        assertTrue(supervisor.token() is ReadValue.Unreadable)
        assertEquals(ConnectionPhase.SESSION_UNREADABLE, (supervisor.current() as PortResult.Success).value.connection)
        assertTrue(files.preserve.isEmpty())
        assertSafe(logger, null, "private-joined")
    }
}

internal class MemorySessionFiles : SessionFilePolicy.Files {
    var bytes: ByteArray? = null
    var reads = 0
    var onRead: suspend () -> Unit = {}
    val preserve = mutableListOf<Boolean>()
    var replaceResult: PortResult<Unit> = PortResult.Success(Unit)
    override suspend fun read(): ReadValue<ByteArray> {
        reads++; onRead()
        return bytes?.let { ReadValue.Present(it.copyOf()) } ?: ReadValue.Absent
    }
    override suspend fun replace(encrypted: ByteArray, preserveUnreadable: Boolean): PortResult<Unit> {
        preserve += preserveUnreadable
        if (replaceResult is PortResult.Success) bytes = encrypted.copyOf()
        return replaceResult
    }
}

internal class PolicyCipher : SessionCipher {
    var decrypts = 0
    var onDecrypt: suspend () -> Unit = {}
    var onEncrypt: suspend () -> Unit = {}
    var decrypted: ByteArray? = null
    var encryptFailure: PortResult.Failure? = null
    override suspend fun encrypt(plaintext: ByteArray): PortResult<ByteArray> {
        onEncrypt()
        return encryptFailure ?: PortResult.Success(scramble(plaintext))
    }
    override suspend fun decrypt(ciphertext: ByteArray): PortResult<ByteArray> {
        decrypts++; onDecrypt()
        return PortResult.Success(decrypted?.copyOf() ?: scramble(ciphertext))
    }
    private fun scramble(bytes: ByteArray) = bytes.map { (it.toInt() xor 0x5a).toByte() }.toByteArray()
}

internal fun assertSafe(logger: FakeLogger, cause: Throwable?, vararg markers: String) {
    val logs = logger.entries.joinToString { "${it.component} ${it.operation} ${it.context} ${it.cause?.stackTraceToString()}" }
    markers.forEach { assertFalse("A private fixture entered diagnostics.", (logs + cause?.stackTraceToString()).contains(it)) }
}
