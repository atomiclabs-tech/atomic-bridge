package tech.atomiclabs.link.ui

import android.app.AppOpsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import kotlin.coroutines.CoroutineContext
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import tech.atomiclabs.link.core.fakes.FakeLogger
import tech.atomiclabs.link.core.port.*

@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("DEPRECATION")
class AndroidRestrictedSettingsTest {
    private val context = mockk<Context>()
    private val operations = mockk<AppOpsManager>()
    private val logger = FakeLogger()
    private val operation = "android:access_restricted_settings"

    private fun adapter(dispatcher: CoroutineDispatcher): AndroidRestrictedSettings {
        every { context.getSystemService(AppOpsManager::class.java) } returns operations
        every { context.applicationInfo } returns ApplicationInfo().apply { uid = 12345 }
        every { context.packageName } returns "fixture.actual.package"
        return AndroidRestrictedSettings(context, logger, dispatcher)
    }

    @Test fun rawSelfObservationDistinguishesExplicitAllowedAndDeniedModes() = runTest {
        val reader = adapter(StandardTestDispatcher(testScheduler))
        val cases = mapOf(
            AppOpsManager.MODE_ALLOWED to RestrictedSettingsStatus.ALLOWED,
            AppOpsManager.MODE_IGNORED to RestrictedSettingsStatus.RESTRICTED,
            AppOpsManager.MODE_ERRORED to RestrictedSettingsStatus.RESTRICTED,
        )
        for ((mode, expected) in cases) {
            every { operations.unsafeCheckOpRawNoThrow(operation, 12345, "fixture.actual.package") } returns mode
            assertEquals(PortResult.Success(expected), reader.read())
        }
        verify(exactly = 3) { operations.unsafeCheckOpRawNoThrow(operation, 12345, "fixture.actual.package") }
        assertTrue(logger.entries.isEmpty())
    }

    @Test fun defaultsForegroundAndUnknownModesNeverInventAllowedAndWarnOnEveryRead() = runTest {
        val reader = adapter(StandardTestDispatcher(testScheduler))
        for (mode in listOf(AppOpsManager.MODE_DEFAULT, AppOpsManager.MODE_FOREGROUND, -100)) {
            every { operations.unsafeCheckOpRawNoThrow(any(), any(), any()) } returns mode
            repeat(2) { assertEquals(PortResult.Success(RestrictedSettingsStatus.UNSUPPORTED), reader.read()) }
        }
        assertEquals(6, logger.entries.size)
        assertTrue(logger.entries.all { it.level == "warn" && it.context.startsWith("state=unknown mode=") })
    }

    @Test fun unrecognizedOperationIsExplicitUnsupportedWithSanitizedWarning() = runTest {
        val reader = adapter(StandardTestDispatcher(testScheduler))
        val cause = IllegalArgumentException("private-platform-fixture")
        every { operations.unsafeCheckOpRawNoThrow(any(), any(), any()) } throws cause
        assertEquals(PortResult.Success(RestrictedSettingsStatus.UNSUPPORTED), reader.read())
        val entry = logger.entries.single()
        assertEquals("warn", entry.level)
        assertFalse(entry.cause!!.stackTraceToString().contains("private-platform-fixture"))
        assertTrue(entry.cause.stackTrace.contentEquals(cause.stackTrace))
    }

    @Test fun protectedProbeDenialIsWarnedOnceAndRememberedUntilReaderReplaced() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val reader = adapter(dispatcher)
        val cause = SecurityException("private-probe-fixture", IllegalStateException("private-cause-fixture"))
        every { operations.unsafeCheckOpRawNoThrow(any(), any(), any()) } throws cause
        repeat(3) { assertEquals(PortResult.Success(RestrictedSettingsStatus.UNSUPPORTED), reader.read()) }
        verify(exactly = 1) { operations.unsafeCheckOpRawNoThrow(operation, 12345, "fixture.actual.package") }
        val entry = logger.entries.single()
        assertEquals("warn", entry.level)
        assertEquals("read", entry.operation)
        assertTrue(entry.context.contains("capability=unavailable"))
        assertFalse(entry.cause!!.stackTraceToString().contains("private-"))
        assertTrue(entry.cause.stackTrace.contentEquals(cause.stackTrace))
        every { operations.unsafeCheckOpRawNoThrow(any(), any(), any()) } returns AppOpsManager.MODE_ALLOWED
        assertEquals(PortResult.Success(RestrictedSettingsStatus.UNSUPPORTED), reader.read())
        verify(exactly = 1) { operations.unsafeCheckOpRawNoThrow(any(), any(), any()) }
        assertEquals(PortResult.Success(RestrictedSettingsStatus.ALLOWED), adapter(dispatcher).read())
        verify(exactly = 2) { operations.unsafeCheckOpRawNoThrow(any(), any(), any()) }
        assertEquals(1, logger.entries.size)
    }

    @Test fun protectedProbeDenialCannotRaceIntoRepeatedProbesFromSharedReads() = runTest {
        val reader = adapter(StandardTestDispatcher(testScheduler))
        every { operations.unsafeCheckOpRawNoThrow(any(), any(), any()) } throws SecurityException("private-probe-fixture")
        val results = List(16) { async { reader.read() } }.awaitAll()
        assertTrue(results.all { it == PortResult.Success(RestrictedSettingsStatus.UNSUPPORTED) })
        verify(exactly = 1) { operations.unsafeCheckOpRawNoThrow(any(), any(), any()) }
        assertEquals("warn", logger.entries.single().level)
    }

    @Test fun failedPlatformReadsKeepTypedCauseAndLogWithoutPrivateMessages() = runTest {
        val reader = adapter(StandardTestDispatcher(testScheduler))
        val cause = IllegalStateException("private-failure-fixture")
        every { operations.unsafeCheckOpRawNoThrow(any(), any(), any()) } throws cause
        val failure = (reader.read() as PortResult.Failure).failure
        assertEquals(FailureKind.PLATFORM, failure.kind)
        assertTrue(generateSequence(failure.cause) { it.cause }.any { it === cause })
        assertEquals("error", logger.entries.single().level)
        assertFalse(logger.entries.single().cause!!.stackTraceToString().contains("private-"))
        every { operations.unsafeCheckOpRawNoThrow(any(), any(), any()) } returns AppOpsManager.MODE_ALLOWED
        assertEquals(PortResult.Success(RestrictedSettingsStatus.ALLOWED), reader.read())
    }

    @Test fun unrelatedSecurityFailureRemainsTypedAndDoesNotDisableTheProbe() = runTest {
        val reader = adapter(StandardTestDispatcher(testScheduler))
        val cause = SecurityException("private-service-fixture")
        every { context.getSystemService(AppOpsManager::class.java) } throws cause
        val failure = (reader.read() as PortResult.Failure).failure
        assertEquals(FailureKind.PERMISSION_DENIED, failure.kind)
        assertTrue(generateSequence(failure.cause) { it.cause }.any { it === cause })
        assertEquals("error", logger.entries.single().level)
        assertFalse(logger.entries.single().cause!!.stackTraceToString().contains("private-"))
        every { context.getSystemService(AppOpsManager::class.java) } returns operations
        every { operations.unsafeCheckOpRawNoThrow(any(), any(), any()) } returns AppOpsManager.MODE_ALLOWED
        assertEquals(PortResult.Success(RestrictedSettingsStatus.ALLOWED), reader.read())
        verify(exactly = 1) { operations.unsafeCheckOpRawNoThrow(any(), any(), any()) }
    }

    @Test fun missingServiceIsUnavailableRatherThanAllowed() = runTest {
        val reader = adapter(StandardTestDispatcher(testScheduler))
        every { context.getSystemService(AppOpsManager::class.java) } returns null
        val failure = (reader.read() as PortResult.Failure).failure
        assertEquals(FailureKind.UNAVAILABLE, failure.kind)
        assertEquals("error", logger.entries.single().level)
        verify(exactly = 0) { operations.unsafeCheckOpRawNoThrow(any(), any(), any()) }
    }

    @Test fun waitingForPlatformDispatcherTimesOutWithoutRetryingTheRead() = runTest {
        val queued = ArrayDeque<Runnable>()
        val stalled = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) { queued.addLast(block) }
        }
        val reader = adapter(stalled)
        val pending = async { reader.read() }
        runCurrent()
        advanceTimeBy(5_000); runCurrent()
        while (queued.isNotEmpty()) queued.removeFirst().run()
        runCurrent()
        assertEquals(FailureKind.TIMED_OUT, (pending.await() as PortResult.Failure).failure.kind)
        assertEquals("error", logger.entries.single().level)
        verify(exactly = 0) { operations.unsafeCheckOpRawNoThrow(any(), any(), any()) }
    }

    @Test fun lifecycleCancellationPropagatesWithoutBecomingAPermissionFailure() = runTest {
        val reader = adapter(StandardTestDispatcher(testScheduler))
        every { operations.unsafeCheckOpRawNoThrow(any(), any(), any()) } throws CancellationException("cancelled")
        val pending = async { reader.read() }
        runCurrent()
        assertTrue(pending.isCancelled)
        assertTrue(logger.entries.isEmpty())
        every { operations.unsafeCheckOpRawNoThrow(any(), any(), any()) } returns AppOpsManager.MODE_ALLOWED
        assertEquals(PortResult.Success(RestrictedSettingsStatus.ALLOWED), reader.read())
    }
}
