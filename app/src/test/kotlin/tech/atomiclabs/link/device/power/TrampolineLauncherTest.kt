package tech.atomiclabs.link.device.power

import android.content.Context
import android.content.Intent
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import tech.atomiclabs.link.core.command.DisplayGateResult
import tech.atomiclabs.link.core.fakes.FakeClock
import tech.atomiclabs.link.core.fakes.FakeLogger
import tech.atomiclabs.link.core.port.*
import tech.atomiclabs.link.core.power.DisplayWaker

@OptIn(ExperimentalCoroutinesApi::class)
class TrampolineLauncherTest {
    private fun intents(id: CapturingSlot<Long>) {
        mockkConstructor(Intent::class)
        every { anyConstructed<Intent>().addFlags(any()) } answers { self as Intent }
        every { anyConstructed<Intent>().putExtra(TrampolineLauncher.EXTRA_JOB, any<String>()) } answers { self as Intent }
        every { anyConstructed<Intent>().putExtra(TrampolineLauncher.EXTRA_ID, capture(id)) } answers { self as Intent }
    }

    @Test fun registeredCallbackDeliversClipboardTextOnceAndIntentChoosesTheJob() = runTest {
        val id = slot<Long>()
        intents(id)
        try {
            val context = mockk<Context>()
            val launcher = TrampolineLauncher(context, FakeLogger(), StandardTestDispatcher(testScheduler))
            every { context.startActivity(any()) } answers {
                assertTrue(launcher.complete(id.captured, PortResult.Success("clipboard fixture")))
            }
            assertEquals(PortResult.Success("clipboard fixture"), launcher.readClipboard())
            assertFalse(launcher.complete(id.captured, PortResult.Success("late")))
            verify { anyConstructed<Intent>().putExtra(TrampolineLauncher.EXTRA_JOB, TrampolineLauncher.CLIPBOARD) }
            verify { anyConstructed<Intent>().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION) }
            every { context.startActivity(any()) } answers { launcher.complete(id.captured, PortResult.Success(null)); Unit }
            assertEquals(PortResult.Success(Unit), launcher.wakeAndDismiss())
            verify { anyConstructed<Intent>().putExtra(TrampolineLauncher.EXTRA_JOB, TrampolineLauncher.DISMISS) }
        } finally { unmockkConstructor(Intent::class) }
    }

    @Test fun missingCallbackTimesOutAtThreeSecondsAndRefusedLaunchKeepsCauseWithoutLeakingIt() = runTest {
        val id = slot<Long>()
        intents(id)
        try {
            val context = mockk<Context>()
            every { context.startActivity(any()) } just Runs
            val logger = FakeLogger()
            val launcher = TrampolineLauncher(context, logger, StandardTestDispatcher(testScheduler))
            val result = async { launcher.readClipboard() }
            runCurrent(); advanceTimeBy(2_999); runCurrent()
            assertFalse(result.isCompleted)
            advanceTimeBy(1); runCurrent()
            assertEquals(FailureKind.TIMED_OUT, (result.await() as PortResult.Failure).failure.kind)
            assertFalse(launcher.complete(id.captured, PortResult.Success("late")))
            assertEquals(3_000, currentTime)
            val cause = IllegalStateException("private-fixture-content")
            every { context.startActivity(any()) } throws cause
            val failure = launcher.wakeAndDismiss() as PortResult.Failure
            assertTrue(generateSequence(failure.failure.cause) { it.cause }.any { it === cause })
            assertFalse(logger.entries.any { it.cause!!.stackTraceToString().contains("private-fixture-content") })
        } finally { unmockkConstructor(Intent::class) }
    }

    @Test fun callerCancellationRemovesItsCallbackAndNullClipboardSuccessIsRejected() = runTest {
        val id = slot<Long>()
        intents(id)
        try {
            val context = mockk<Context>()
            every { context.startActivity(any()) } just Runs
            val launcher = TrampolineLauncher(context, FakeLogger(), StandardTestDispatcher(testScheduler))
            val pending = launch { launcher.readClipboard() }
            runCurrent(); pending.cancelAndJoin()
            assertFalse(launcher.complete(id.captured, PortResult.Success("late")))
            every { context.startActivity(any()) } answers { launcher.complete(id.captured, PortResult.Success(null)); Unit }
            assertEquals(FailureKind.INVALID_DATA, (launcher.readClipboard() as PortResult.Failure).failure.kind)
        } finally { unmockkConstructor(Intent::class) }
    }

    @Test fun wakeDeadlineChecksInteractivityAndCleansUpTheRealLauncherCallback() = runTest {
        val id = slot<Long>()
        intents(id)
        try {
            var interactive = false
            val power = object : PowerState {
                override suspend fun isInteractive() = PortResult.Success(interactive)
                override suspend fun wakeWithBrightLock(durationMs: Long) = PortResult.Success(Unit)
            }
            val context = mockk<Context>()
            every { context.startActivity(any()) } answers {
                backgroundScope.launch { delay(1_500); interactive = true }
                Unit
            }
            val logger = FakeLogger()
            val launcher = TrampolineLauncher(context, logger, StandardTestDispatcher(testScheduler))
            val waker = DisplayWaker(power, KeyguardState { PortResult.Success(KeyguardSnapshot(false, false)) },
                launcher, FakeClock(testScheduler), logger)
            assertEquals(DisplayGateResult.Ready, waker.prepare())
            assertEquals(4_300, currentTime)
            assertFalse(launcher.complete(id.captured, PortResult.Success(null)))
            assertFalse(logger.entries.any { it.level == "error" })
            verify(exactly = 1) { context.startActivity(any()) }
        } finally { unmockkConstructor(Intent::class) }
    }
}
