package tech.atomiclabs.link.service

import android.os.BatteryManager
import io.mockk.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import tech.atomiclabs.link.core.fakes.*
import tech.atomiclabs.link.core.port.*
import tech.atomiclabs.link.core.screen.WindowOrder

class AndroidDeviceStateTest {
    @Test fun disconnectedAccessibilityKeepsRealBatteryAndPowerWithoutReadingWindows() = runTest {
        val tree = mockk<UiTreeSource>()
        coEvery { tree.isConnected() } returns PortResult.Success(false)
        val battery = mockk<BatteryManager>()
        every { battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) } returns 72
        val power = mockk<PowerState>()
        coEvery { power.isInteractive() } returns PortResult.Success(false)
        val logger = FakeLogger()
        val adapter = AndroidDeviceState(battery, power, tree, WindowOrder(tree, logger), { error("No package") },
            logger, StandardTestDispatcher(testScheduler))
        assertEquals(PortResult.Success(DeviceSnapshot(72, false, null, null)), adapter.read())
        coVerify(exactly = 0) { tree.windows(); tree.activeRoot() }
    }

    @Test fun usesSharedApplicationResolverAndPropagatesReadFailures() = runTest {
        val app = FakeUiNode(UiNodeProperties(Bounds(0, 0, 5, 5), 9, packageName = "fixture.app"))
        val system = FakeUiNode(UiNodeProperties(Bounds(0, 0, 5, 5), 10, packageName = "com.android.systemui"))
        val tree = FakeUiTree().apply { windowResult = PortResult.Success(listOf(
            UiWindow(10, WindowType.SYSTEM, 3, 10, true, true, system, Bounds(0, 0, 5, 5)),
            UiWindow(9, WindowType.APPLICATION, 1, 2, true, false, app, Bounds(0, 0, 5, 5)))) }
        val battery = mockk<BatteryManager>()
        every { battery.getIntProperty(any()) } returns 60
        val power = mockk<PowerState>()
        coEvery { power.isInteractive() } returns PortResult.Success(true)
        val logger = FakeLogger()
        val adapter = AndroidDeviceState(battery, power, tree, WindowOrder(tree, logger), { "$it.Activity" },
            logger, StandardTestDispatcher(testScheduler))
        assertEquals(PortResult.Success(DeviceSnapshot(60, true, "fixture.app", "fixture.app.Activity")), adapter.read())
        tree.windowResult = PortResult.Failure(PortFailure(FailureKind.PLATFORM, IllegalStateException("private-tree")))
        assertTrue(adapter.read() is PortResult.Failure)
        every { battery.getIntProperty(any()) } returns Int.MIN_VALUE
        assertTrue(adapter.read() is PortResult.Failure)
        assertEquals(2, logger.entries.count { it.level == "error" })
        assertFalse(logger.entries.any { it.cause!!.stackTraceToString().contains("private-tree") })
    }
}
