package tech.atomiclabs.link.core.unlock

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import tech.atomiclabs.link.core.protocol.BridgeCommand

@OptIn(ExperimentalCoroutinesApi::class)
class UnlockConcurrencyTest {
    @Test fun distinctUnlockCallsUseTheRealRuntimeMutexWithoutInterleaving() = runTest {
        val f = UnlockSafetyFixture(this)
        val release = CompletableDeferred<Unit>()
        f.onDigit = { if (f.digitActions == 1) release.await() }
        f.onConfirm = { f.showing = false }
        val first = async { f.runtime.router.dispatch(unlockCommand("first", generatedPin())) }
        runCurrent()
        val second = async { f.runtime.router.dispatch(unlockCommand("second", generatedPin())) }
        runCurrent()
        val beforeRelease = f.digitActions
        release.complete(Unit)
        first.await(); second.await()
        assertTrue("A second unlock must not begin input while the first owns the pad.", beforeRelease == 1)
        assertTrue("Unlock must keep bypassing the display gate.", f.gate.calls == 0)
    }

    @Test fun tapCannotOvertakeUnlock() = runTest {
        assertExclusiveInput(BridgeCommand("tap", "POST", "/tap", body = buildJsonObject { put("x", 5); put("y", 5) }))
    }

    @Test fun typeCannotOvertakeUnlock() = runTest {
        assertExclusiveInput(BridgeCommand("type", "POST", "/type", body = buildJsonObject { put("text", "ordinary") }))
    }

    @Test fun homeCannotOvertakeUnlock() = runTest { assertExclusiveInput(key("home")) }
    @Test fun powerCannotOvertakeUnlock() = runTest { assertExclusiveInput(key("power")) }
    @Test fun volumeCannotOvertakeUnlock() = runTest { assertExclusiveInput(key("volume_up")) }

    private fun key(name: String) = BridgeCommand(name, "POST", "/press_key",
        body = buildJsonObject { put("key", name) })

    private suspend fun TestScope.assertExclusiveInput(command: BridgeCommand) {
        val f = UnlockSafetyFixture(this)
        val release = CompletableDeferred<Unit>()
        f.onDigit = { if (f.digitActions == 1) release.await() }
        f.onConfirm = { f.showing = false }
        val unlock = async { f.runtime.router.dispatch(unlockCommand("owner", generatedPin())) }
        runCurrent()
        val input = async { f.runtime.router.dispatch(command) }
        runCurrent()
        val overtook = f.gate.calls > 0 || f.inputActions > 0 || input.isCompleted
        release.complete(Unit)
        unlock.await(); input.await()
        assertTrue("Input and power/volume commands must wait for unlock ownership.", !overtook)
    }
}
