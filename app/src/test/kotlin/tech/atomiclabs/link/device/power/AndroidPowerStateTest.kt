package tech.atomiclabs.link.device.power

import android.os.PowerManager
import io.mockk.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import tech.atomiclabs.link.core.fakes.FakeLogger
import tech.atomiclabs.link.core.port.*

class AndroidPowerStateTest {
    @Suppress("DEPRECATION")
    @Test fun readsInteractivityAndChecksBoundedBrightWakeLockWithTheRequiredTagAndFlags() = runTest {
        val manager = mockk<PowerManager>()
        val lock = mockk<PowerManager.WakeLock>(relaxed = true)
        every { manager.isInteractive } returns true
        every { manager.newWakeLock(any(), any()) } returns lock
        every { lock.isHeld } returns true
        val adapter = AndroidPowerState(manager, FakeLogger(), StandardTestDispatcher(testScheduler))
        assertEquals(PortResult.Success(true), adapter.isInteractive())
        assertEquals(PortResult.Success(Unit), adapter.wakeWithBrightLock(10_000))
        verify { manager.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or
            PowerManager.ON_AFTER_RELEASE, "link:wake") }
        verify { lock.setReferenceCounted(false); lock.acquire(10_000) }
    }

    @Test fun acquireRefusalAndPermissionFailuresReturnLoggedFailures() = runTest {
        val manager = mockk<PowerManager>()
        val lock = mockk<PowerManager.WakeLock>(relaxed = true)
        every { manager.newWakeLock(any(), any()) } returns lock
        every { lock.isHeld } returns false
        val logger = FakeLogger()
        val adapter = AndroidPowerState(manager, logger, StandardTestDispatcher(testScheduler))
        assertTrue(adapter.wakeWithBrightLock(10_000) is PortResult.Failure)
        every { lock.acquire(any()) } throws SecurityException("private-fixture")
        val result = adapter.wakeWithBrightLock(10_000) as PortResult.Failure
        assertEquals(FailureKind.PERMISSION_DENIED, result.failure.kind)
        assertEquals("android.permission.WAKE_LOCK", result.failure.permission)
        every { manager.isInteractive } throws IllegalStateException("private-fixture")
        assertTrue(adapter.isInteractive() is PortResult.Failure)
        assertEquals(3, logger.entries.size)
        assertFalse(logger.entries.any { it.cause!!.stackTraceToString().contains("private-fixture") })
    }
}
