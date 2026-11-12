package tech.atomiclabs.link.storage

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.*
import javax.crypto.spec.SecretKeySpec
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import tech.atomiclabs.link.core.connection.*
import tech.atomiclabs.link.core.fakes.*
import tech.atomiclabs.link.core.port.*

@OptIn(ExperimentalCoroutinesApi::class)
class FileSessionStoreTest {
    @get:Rule val temp = TemporaryFolder(File(requireNotNull(System.getenv("TMPDIR"))))

    @Test fun timedOutStagingDoesNotReplaceCommittedBytesOrAlterTheArchive() = runTest {
        for (preserve in listOf(false, true)) {
            val directory = temp.newFolder()
            val before = byteArrayOf(1, 2, 3)
            val target = File(directory, "session.bin").apply { writeBytes(before) }
            val archive = File(directory, "session.bin.unreadable").apply { writeBytes(byteArrayOf(4)) }
            val logger = FakeLogger()
            var moves = 0
            val files = FileSessionStore(directory, logger, StandardTestDispatcher(testScheduler),
                writeStaged = { staged, bytes -> staged.writeBytes(bytes); testScheduler.advanceTimeBy(2_001) },
                move = { _, _ -> moves++ })
            val result = files.replace(byteArrayOf(8), preserve) as PortResult.Failure
            assertEquals(FailureKind.TIMED_OUT, result.failure.kind)
            assertArrayEquals(before, target.readBytes())
            assertArrayEquals(byteArrayOf(4), archive.readBytes())
            assertEquals(0, moves)
            assertEquals(setOf("session.bin", "session.bin.unreadable"), directory.list()!!.toSet())
            assertEquals("replace", logger.entries.single { it.level == "error" }.operation)
        }
    }

    @Test fun timeoutsDuringArchiveOrFinalRenameRestoreTheWholePreviousFileState() = runTest {
        for (point in listOf("archive", "promote", "new")) {
            val directory = temp.newFolder()
            val before = byteArrayOf(1, 2, 3)
            val target = File(directory, "session.bin")
            if (point != "new") target.writeBytes(before)
            val archive = File(directory, "session.bin.unreadable").apply { writeBytes(byteArrayOf(4)) }
            val files = FileSessionStore(directory, FakeLogger(), StandardTestDispatcher(testScheduler), move = { from, to ->
                Files.move(from.toPath(), to.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
                if (from.name == "session.bin" && point == "archive" || from.name.endsWith(".tmp") && point != "archive") {
                    testScheduler.advanceTimeBy(2_001)
                }
            })
            val result = files.replace(byteArrayOf(8), true) as PortResult.Failure
            assertEquals(FailureKind.TIMED_OUT, result.failure.kind)
            if (point == "new") assertFalse(target.exists()) else assertArrayEquals(before, target.readBytes())
            assertArrayEquals(byteArrayOf(4), archive.readBytes())
            assertEquals(if (point == "new") setOf("session.bin.unreadable")
                else setOf("session.bin", "session.bin.unreadable"), directory.list()!!.toSet())
        }
    }

    @Test fun cancellationDuringStagingOrPromotionRestoresTheOldBytesAndPropagates() = runTest {
        for (staging in listOf(true, false)) {
            val directory = temp.newFolder()
            val before = byteArrayOf(1, 2, 3)
            val target = File(directory, "session.bin").apply { writeBytes(before) }
            val archive = File(directory, "session.bin.unreadable").apply { writeBytes(byteArrayOf(4)) }
            lateinit var job: Deferred<PortResult<Unit>>
            val files = FileSessionStore(directory, FakeLogger(), StandardTestDispatcher(testScheduler),
                writeStaged = { file, bytes -> file.writeBytes(bytes); if (staging) job.cancel() },
                move = { from, to ->
                    Files.move(from.toPath(), to.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
                    if (from.name.endsWith(".tmp")) job.cancel()
                })
            job = backgroundScope.async { files.replace(byteArrayOf(8), true) }
            runCurrent(); job.join()
            assertTrue(job.isCancelled)
            assertArrayEquals(before, target.readBytes())
            assertArrayEquals(byteArrayOf(4), archive.readBytes())
            assertEquals(setOf("session.bin", "session.bin.unreadable"), directory.list()!!.toSet())
        }
    }

    @Test fun cancellationAtDispatcherReturnRestoresThePreviousFile() = runTest {
        val directory = temp.newFolder()
        val before = byteArrayOf(1, 2, 3)
        val target = File(directory, "session.bin").apply { writeBytes(before) }
        val queue = StandardTestDispatcher(testScheduler)
        lateinit var job: Deferred<PortResult<Unit>>
        val caller = object : CoroutineDispatcher() {
            var dispatches = 0
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                if (++dispatches == 2) job.cancel()
                queue.dispatch(context, block)
            }
        }
        val files = FileSessionStore(directory, FakeLogger(), queue)
        job = backgroundScope.async(caller) { files.replace(byteArrayOf(8), false) }
        runCurrent(); job.join()
        assertTrue(job.isCancelled)
        assertArrayEquals(before, target.readBytes())
        assertEquals(setOf("session.bin"), directory.list()!!.toSet())
    }

    @Test fun policyTimeoutDuringPromotionDoesNotLeaveTheRejectedSessionOnDisk() = runTest {
        val directory = temp.newFolder()
        val before = byteArrayOf(1, 2, 3)
        val target = File(directory, "session.bin").apply { writeBytes(before) }
        val logger = FakeLogger()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val files = FileSessionStore(directory, logger, dispatcher, move = { from, to ->
            Files.move(from.toPath(), to.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
            if (from.name.endsWith(".tmp")) testScheduler.advanceTimeBy(8_001)
        })
        val key = SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")
        val policy = SessionFilePolicy(files, KeystoreCipher(logger, dispatcher) { key }, FakeClock(testScheduler), logger)
        assertTrue(policy.read() is ReadValue.Unreadable)
        val result = policy.save(originalSession()) as PortResult.Failure
        assertEquals(FailureKind.TIMED_OUT, result.failure.kind)
        assertArrayEquals(before, target.readBytes())
        assertEquals(setOf("session.bin"), directory.list()!!.toSet())
        assertSafe(logger, result.failure.cause, originalSession().sessionToken, originalSession().refreshToken!!)
    }

    @Test fun realEncryptedFileRoundTripsAllSessionFieldsWithoutPlaintext() = runTest {
        val directory = temp.newFolder()
        val logger = FakeLogger()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val key = SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")
        val files = FileSessionStore(directory, logger, dispatcher)
        val cipher = KeystoreCipher(logger, dispatcher) { key }
        val policy = SessionFilePolicy(files, cipher, FakeClock(testScheduler), logger)
        val session = originalSession().copy(savedAtEpochMs = 123, expiresAtEpochSeconds = 456,
            grants = mapOf("bridge" to null, "other" to 789))
        assertEquals(ReadValue.Absent, policy.read())
        assertTrue(policy.save(session) is PortResult.Success)
        val file = File(directory, "session.bin")
        val first = file.readBytes()
        assertFalse(first.decodeToString().contains(session.sessionToken))
        assertFalse(first.decodeToString().contains(session.refreshToken!!))
        assertEquals(ReadValue.Present(session), policy.read())
        assertTrue(policy.save(session) is PortResult.Success)
        assertFalse(first.contentEquals(file.readBytes()))
        assertEquals(setOf("session.bin"), directory.list()!!.toSet())
        assertSafe(logger, null, session.sessionToken, session.refreshToken)
    }

    @Test fun unreadableFileIsUnchangedAfterRetriesThenArchivedBeforeSuccessfulReplacement() = runTest {
        val directory = temp.newFolder()
        val old = byteArrayOf(3, 1, 4, 1)
        val target = File(directory, "session.bin").apply { writeBytes(old) }
        File(directory, "session.bin.unreadable").writeBytes(byteArrayOf(9))
        val moves = mutableListOf<Pair<String, String>>()
        val logger = FakeLogger()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val files = FileSessionStore(directory, logger, dispatcher, move = { from, to ->
            moves += from.name to to.name
            Files.move(from.toPath(), to.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        })
        val key = SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")
        val policy = SessionFilePolicy(files, KeystoreCipher(logger, dispatcher) { key }, FakeClock(testScheduler), logger)
        assertTrue(policy.read() is ReadValue.Unreadable)
        assertEquals(3_000L, testScheduler.currentTime)
        assertArrayEquals(old, target.readBytes())
        assertTrue(policy.save(originalSession()) is PortResult.Success)
        assertEquals("session.bin" to "session.bin.unreadable", moves[0])
        assertTrue(moves[1].first.endsWith(".tmp"))
        assertEquals("session.bin", moves[1].second)
        assertArrayEquals(old, File(directory, "session.bin.unreadable").readBytes())
        assertEquals(ReadValue.Present(originalSession()), policy.read())
        assertEquals(setOf("session.bin", "session.bin.unreadable"), directory.list()!!.toSet())
        assertSafe(logger, null, originalSession().sessionToken)
    }

    @Test fun failedStagedWriteDoesNotRenameOrChangeTheOldFile() = runTest {
        val directory = temp.newFolder()
        val before = byteArrayOf(1, 2, 3)
        val target = File(directory, "session.bin").apply { writeBytes(before) }
        val logger = FakeLogger()
        val files = FileSessionStore(directory, logger, StandardTestDispatcher(testScheduler), writeStaged = { file, _ ->
            file.writeBytes(byteArrayOf(7)); throw IOException("private-partial-write")
        })
        val result = files.replace(byteArrayOf(8), true) as PortResult.Failure
        assertArrayEquals(before, target.readBytes())
        assertEquals(setOf("session.bin"), directory.list()!!.toSet())
        assertSafe(logger, result.failure.cause, "private-partial-write")
    }

    @Test fun failedFinalRenameRestoresTheArchivedOldFileAndRemovesOnlyTheTemp() = runTest {
        val directory = temp.newFolder()
        val before = byteArrayOf(1, 2, 3)
        val target = File(directory, "session.bin").apply { writeBytes(before) }
        val logger = FakeLogger()
        val files = FileSessionStore(directory, logger, StandardTestDispatcher(testScheduler), move = { from, to ->
            if (from.name.endsWith(".tmp")) throw IOException("private-final-rename")
            Files.move(from.toPath(), to.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        })
        val result = files.replace(byteArrayOf(8), true) as PortResult.Failure
        assertArrayEquals(before, target.readBytes())
        assertEquals(setOf("session.bin"), directory.list()!!.toSet())
        assertSafe(logger, result.failure.cause, "private-final-rename")
    }

    @Test fun failedArchiveDoesNotTouchTheOldTargetOrPriorArchive() = runTest {
        val directory = temp.newFolder()
        val target = File(directory, "session.bin").apply { writeBytes(byteArrayOf(1)) }
        val archive = File(directory, "session.bin.unreadable").apply { writeBytes(byteArrayOf(2)) }
        val logger = FakeLogger()
        val files = FileSessionStore(directory, logger, StandardTestDispatcher(testScheduler), move = { _, _ ->
            throw IOException("private-archive-rename")
        })
        val result = files.replace(byteArrayOf(8), true) as PortResult.Failure
        assertArrayEquals(byteArrayOf(1), target.readBytes())
        assertArrayEquals(byteArrayOf(2), archive.readBytes())
        assertEquals(2, directory.list()!!.size)
        assertSafe(logger, result.failure.cause, "private-archive-rename")
    }

    @Test fun failedReplacementOfReadableFilePreservesTheOldBytes() = runTest {
        val directory = temp.newFolder()
        val target = File(directory, "session.bin").apply { writeBytes(byteArrayOf(1)) }
        val files = FileSessionStore(directory, FakeLogger(), StandardTestDispatcher(testScheduler), move = { _, _ ->
            throw IOException("private-replace")
        })
        assertTrue(files.replace(byteArrayOf(2), false) is PortResult.Failure)
        assertArrayEquals(byteArrayOf(1), target.readBytes())
        assertEquals(setOf("session.bin"), directory.list()!!.toSet())
    }

    @Test fun unavailableDirectoryAndReadErrorDoNotBecomeAnAbsentSession() = runTest {
        val logger = FakeLogger()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val missing = FileSessionStore(File(temp.root, "not-created"), logger, dispatcher)
        assertTrue(missing.read() is ReadValue.Unreadable)
        assertTrue(missing.replace(byteArrayOf(1), false) is PortResult.Failure)
        val directory = temp.newFolder()
        File(directory, "session.bin").mkdir()
        assertTrue(FileSessionStore(directory, logger, dispatcher).read() is ReadValue.Unreadable)
        assertEquals(3, logger.entries.count { it.level == "error" })
    }
}
