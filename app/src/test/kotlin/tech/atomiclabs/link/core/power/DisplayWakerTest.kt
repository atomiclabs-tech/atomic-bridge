package tech.atomiclabs.link.core.power

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import tech.atomiclabs.link.core.command.*
import tech.atomiclabs.link.core.fakes.*
import tech.atomiclabs.link.core.port.*

@OptIn(ExperimentalCoroutinesApi::class)
class DisplayWakerTest {
    private class Power : PowerState {
        var interactive = true
        val locks = mutableListOf<Long>()
        var failure: PortFailure? = null
        override suspend fun isInteractive(): PortResult<Boolean> = failure?.let { PortResult.Failure(it) }
            ?: PortResult.Success(interactive)
        override suspend fun wakeWithBrightLock(durationMs: Long): PortResult<Unit> {
            locks += durationMs
            return PortResult.Success(Unit)
        }
    }
    private class Guard : KeyguardState {
        var showing = false
        var secure = false
        override suspend fun read() = PortResult.Success(KeyguardSnapshot(showing, secure))
    }
    private class Activity : Trampoline {
        var calls = 0
        var action: suspend () -> Unit = {}
        var result: PortResult<Unit> = PortResult.Success(Unit)
        override suspend fun wakeAndDismiss(): PortResult<Unit> { calls++; action(); return result }
        override suspend fun readClipboard(): PortResult<String> = error("Clipboard route is outside this test")
    }
    private val power = Power()
    private val guard = Guard()
    private val activity = Activity()
    private val logger = FakeLogger()
    private fun TestScope.waker() = DisplayWaker(power, guard, activity, FakeClock(testScheduler), logger)
    private fun assertCode(result: DisplayGateResult, code: ErrorCode) {
        assertTrue("Expected $code, received $result", result is DisplayGateResult.Failed)
        assertEquals(code, (result as DisplayGateResult.Failed).failure.code)
    }

    @Test fun alreadyOnAndUnlockedHasNoWakeTrampolineOrDelay() = runTest {
        assertEquals(DisplayGateResult.Ready, waker().prepare())
        assertTrue(power.locks.isEmpty())
        assertEquals(0, activity.calls)
        assertEquals(0, currentTime)
    }

    @Test fun brightLockWakesWithinTwoSecondsThenSettlesExactly300ms() = runTest {
        power.interactive = false
        launch { delay(1_000); power.interactive = true }
        assertEquals(DisplayGateResult.Ready, waker().prepare())
        assertEquals(listOf(10_000L), power.locks)
        assertEquals(0, activity.calls)
        assertEquals(1_300, currentTime)
    }

    @Test fun wakeFallbackWarnsAndSuccessSettlesOnce() = runTest {
        power.interactive = false
        activity.action = { power.interactive = true }
        assertEquals(DisplayGateResult.Ready, waker().prepare())
        assertEquals(1, activity.calls)
        assertEquals(2_300, currentTime)
        assertEquals(1, logger.entries.count { it.level == "warn" && it.operation == "trampolineFallback" })
    }

    @Test fun screenStillOffAfterBothAttemptsReturnsLoggedWakeFailureAtFourSeconds() = runTest {
        power.interactive = false
        assertCode(waker().prepare(), ErrorCode.WAKE_FAILED)
        assertEquals(1, activity.calls)
        assertEquals(4_000, currentTime)
        assertTrue(logger.entries.any { it.level == "warn" && it.context.contains("wake_failed") })
    }

    @Test fun slowWakeCallbackCannotExtendTwoSecondFallbackAndIsCancelledAtItsDeadline() = runTest {
        power.interactive = false
        var cancelled = false
        activity.action = {
            try { delay(2_500) } catch (cause: CancellationException) { cancelled = true; throw cause }
        }
        assertCode(waker().prepare(), ErrorCode.WAKE_FAILED)
        assertEquals(4_000, currentTime)
        assertTrue(cancelled)
        assertEquals(1, activity.calls)
        assertTrue(logger.entries.any { it.level == "warn" && it.operation == "wakeTrampolineDeadline" })
    }

    @Test fun callbackTimeConsumesTheSameBudgetAndAChangeAfterTheDeadlineCannotBecomeSuccess() = runTest {
        power.interactive = false
        activity.action = { delay(1_500) }
        backgroundScope.launch { delay(4_100); power.interactive = true }
        assertCode(waker().prepare(), ErrorCode.WAKE_FAILED)
        assertEquals(4_000, currentTime)
        assertFalse(power.interactive)
    }

    @Test fun actualInteractivityWithinTheBudgetCanSucceedDespiteALateCallback() = runTest {
        power.interactive = false
        var cancelled = false
        activity.action = {
            try { delay(2_500) } catch (cause: CancellationException) { cancelled = true; throw cause }
        }
        backgroundScope.launch { delay(3_500); power.interactive = true }
        assertEquals(DisplayGateResult.Ready, waker().prepare())
        assertEquals(4_300, currentTime)
        assertTrue(cancelled)
        assertEquals(1, activity.calls)
    }

    @Test fun typedWakeCallbackFailureBeforeTheDeadlineIsStillLoggedAndReturned() = runTest {
        power.interactive = false
        activity.action = { delay(1_000) }
        activity.result = PortResult.Failure(PortFailure(FailureKind.REJECTED, IllegalStateException("private-fixture")))
        assertCode(waker().prepare(), ErrorCode.WAKE_FAILED)
        assertEquals(3_000, currentTime)
        assertEquals("error", logger.entries.last().level)
        assertFalse(logger.entries.last().cause!!.stackTraceToString().contains("private-fixture"))
    }

    @Test fun secureKeyguardNeverLaunchesTheTrampolineIncludingWakeFallback() = runTest {
        guard.showing = true; guard.secure = true
        assertCode(waker().prepare(), ErrorCode.KEYGUARD_BLOCKED)
        assertEquals(0, activity.calls)
        power.interactive = false
        assertCode(waker().prepare(), ErrorCode.KEYGUARD_BLOCKED)
        assertEquals(0, activity.calls)
        assertTrue(logger.entries.all { it.level == "warn" })
    }

    @Test fun nonSecureKeyguardDismissalIncludesItsCallTimeInThreeSecondDeadlineAndSettles() = runTest {
        guard.showing = true
        activity.action = { delay(2_000); guard.showing = false }
        assertEquals(DisplayGateResult.Ready, waker().prepare())
        assertEquals(2_300, currentTime)
        assertEquals(1, activity.calls)
        assertTrue(power.locks.isEmpty())
    }

    @Test fun nonSecureKeyguardThatRemainsShowingFailsAtThreeSecondsWithoutSettle() = runTest {
        guard.showing = true
        activity.action = { delay(2_000) }
        assertCode(waker().prepare(), ErrorCode.KEYGUARD_BLOCKED)
        assertEquals(3_000, currentTime)
        assertTrue(logger.entries.any { it.level == "warn" })
    }

    @Test fun outsideFailuresAreLoggedWithoutPrivateMessagesAndPermissionMetadataIsPreserved() = runTest {
        power.failure = PortFailure(FailureKind.PERMISSION_DENIED, SecurityException("private-payload"),
            permission = "android.permission.WAKE_LOCK")
        val result = waker().prepare() as DisplayGateResult.Failed
        assertEquals(ErrorCode.PERMISSION_DENIED, result.failure.code)
        assertTrue(result.failure.fields.containsKey("permission"))
        assertFalse(logger.entries.single().cause!!.stackTraceToString().contains("private-payload"))
        power.failure = null
        guard.showing = true
        activity.result = PortResult.Failure(PortFailure(FailureKind.REJECTED, IllegalStateException("private-payload")))
        assertCode(waker().prepare(), ErrorCode.KEYGUARD_BLOCKED)
        assertEquals("error", logger.entries.last().level)
    }
}
