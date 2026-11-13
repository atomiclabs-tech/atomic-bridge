package tech.atomiclabs.link.core.input

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import tech.atomiclabs.link.core.command.*
import tech.atomiclabs.link.core.port.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class KeyCommandsTest {
    @Test fun everyGlobalKeyAndUpstreamAliasMapsToTheMatchingAction() = runTest {
        val f = InputFixture(testScheduler)
        for ((key, action) in listOf("back" to GlobalAction.BACK, "home" to GlobalAction.HOME,
            "recents" to GlobalAction.RECENTS, "recent_apps" to GlobalAction.RECENTS, "overview" to GlobalAction.RECENTS,
            "notifications" to GlobalAction.NOTIFICATIONS, "quick_settings" to GlobalAction.QUICK_SETTINGS,
            "power_dialog" to GlobalAction.POWER_DIALOG, "lock_screen" to GlobalAction.LOCK_SCREEN, "escape" to GlobalAction.BACK)) {
            val result = f.send("/press_key", "key" to key)
            assertEquals(action, f.globals.calls.last()); assertEquals(key, result["key"]!!.jsonPrimitive.content)
            if (key == "escape") assertEquals("back", result["via"]!!.jsonPrimitive.content)
        }
        f.globals.result = refused
        assertEquals("gesture_failed", f.send("/press_key", "key" to "back")["code"]!!.jsonPrimitive.content)
    }

    @Test fun volumeNeedsNeitherAccessibilityNorWakeAndPowerNeverPreWakes() = runTest {
        val f = InputFixture(testScheduler)
        f.tree.connection = PortResult.Success(false)
        f.send("/press_key", "key" to "volume_up"); f.send("/press_key", "key" to "volume_down")
        assertEquals(listOf(VolumeDirection.UP, VolumeDirection.DOWN), f.volume.calls)
        assertEquals(0, f.tree.connectionReads); assertEquals(0, f.gate.calls)
        f.tree.connection = PortResult.Success(true)
        f.gate.result = DisplayGateResult.Failed(CommandResult.Failure(ErrorCode.WAKE_FAILED, "The display stayed off."))
        f.power.interactive = false
        assertEquals("wake", f.send("/press_key", "key" to "power")["via"]!!.jsonPrimitive.content)
        assertEquals(1, f.power.wakes); assertTrue(f.globals.calls.isEmpty())
        assertEquals("lock_screen", f.send("/press_key", "key" to "power")["via"]!!.jsonPrimitive.content)
        assertEquals(listOf(GlobalAction.LOCK_SCREEN), f.globals.calls)
        assertEquals(0, f.gate.calls)
    }

    @Test fun powerAndVolumeShareInputOwnershipWithoutAddingDisplayPreconditions() = runTest {
        val f = InputFixture(testScheduler)
        val keys = f.keys
        for (name in listOf("power", "volume_up", "volume_down")) {
            val needs = keys.needs(BodyParams(buildJsonObject { put("key", name) }))
            assertTrue(needs.exclusive)
            assertFalse(needs.display)
            assertEquals(name == "power", needs.accessibility)
        }
    }

    @Test fun searchAndUnknownKeysListSupportedKeysAndFailedWakeCannotSucceed() = runTest {
        val f = InputFixture(testScheduler)
        for (key in listOf("search", "unknown")) {
            val result = f.send("/press_key", "key" to key)
            assertEquals("unsupported_key", result["code"]!!.jsonPrimitive.content)
            for (supported in KeyCommands.supported) assertTrue(result["error"]!!.jsonPrimitive.content.contains(supported))
        }
        f.power.interactive = false; f.power.wakesScreen = false
        assertEquals("wake_failed", f.send("/press_key", "key" to "power")["code"]!!.jsonPrimitive.content)
    }

    @Test fun powerWaitsForDelayedInteractivityWithoutPreWakingOrLockingAgain() = runTest {
        val f = InputFixture(testScheduler)
        f.power.interactive = false; f.power.wakesScreen = false
        backgroundScope.launch { delay(150); f.power.interactive = true }
        assertEquals("wake", f.send("/press_key", "key" to "power")["via"]!!.jsonPrimitive.content)
        assertEquals(200, currentTime)
        assertEquals(1, f.power.wakes); assertEquals(0, f.gate.calls)
        assertTrue(f.globals.calls.isEmpty())
    }
}
