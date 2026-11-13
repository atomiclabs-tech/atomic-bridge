package tech.atomiclabs.link.core.unlock

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class UnlockDeadlineTest {
    @Test fun realDeadlineDuringEntryReportsAttemptAndReplaysWithoutRepeatingInput() = runTest {
        val f = UnlockSafetyFixture(this)
        f.onDigit = { if (f.digitActions == 2) delay(30_000) }
        val command = unlockCommand("interrupted", generatedPin())
        val first = f.runtime.router.dispatch(command)
        assertTrue("The real default deadline must stop work at its configured budget.", currentTime == 25_000L)
        assertTrue("A started entry must retain truthful attempt metadata.", first.result["pin_attempt_used"] == JsonPrimitive(true))
        assertTrue("Entry interruption must retain its stage.", first.result["stage"] == JsonPrimitive("enter_pin"))
        assertTrue("The timeout result must not expose key data.", first.result.toString().none { it in '0'..'9' })
        val count = f.digitActions
        assertSame(first, f.runtime.router.dispatch(command))
        assertTrue("A replay must never touch the pad again.", f.digitActions == count)
    }

    @Test fun laterCallRefusesToAppendAfterADeadlineLeavesPartialEntry() = runTest {
        val f = UnlockSafetyFixture(this)
        f.onDigit = { if (f.digitActions == 2) delay(30_000) }
        f.runtime.router.dispatch(unlockCommand("partial", generatedPin()))
        val count = f.digitActions
        val second = f.runtime.router.dispatch(unlockCommand("later", generatedPin())).result
        assertTrue("A later request must not append to leftover digits.", f.digitActions == count)
        assertTrue("The later refusal must report that it used no new attempt.", second["pin_attempt_used"] == JsonPrimitive(false))
        f.showing = false
        val recovered = f.runtime.router.dispatch(unlockCommand("clear-keyguard", generatedPin())).result
        assertTrue("A cleared keyguard must release the leftover-entry refusal.", recovered["already_unlocked"] == JsonPrimitive(true))
    }

    @Test fun deadlineBeforeAnyDigitReportsNoAttempt() = runTest {
        val f = UnlockSafetyFixture(this)
        f.beforeWindows = { delay(30_000) }
        val first = f.runtime.router.dispatch(unlockCommand("before-entry", generatedPin())).result
        assertTrue("A deadline before entry must report no attempt.", first["pin_attempt_used"] == JsonPrimitive(false))
        assertTrue("No digit may precede the interrupted pad read.", f.digitActions == 0)
    }

    @Test fun callerCancellationDuringEntryIsReplayedAndCannotLeaveThePadReusable() = runTest {
        val f = UnlockSafetyFixture(this)
        f.onDigit = { awaitCancellation() }
        val command = unlockCommand("cancelled", generatedPin())
        val owner = launch { f.runtime.router.dispatch(command) }
        runCurrent()
        owner.cancelAndJoin()
        val replay = f.runtime.router.dispatch(command).result
        assertTrue("Cancellation after dispatching a digit must retain its attempt.", replay["pin_attempt_used"] == JsonPrimitive(true))
        val count = f.digitActions
        f.onDigit = {}
        f.runtime.router.dispatch(unlockCommand("after-cancellation", generatedPin()))
        assertTrue("Cancellation must leave a refusal instead of appending input.", f.digitActions == count)
    }
}
