package tech.atomiclabs.link.device.access

import android.accessibilityservice.AccessibilityService
import io.mockk.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import tech.atomiclabs.link.core.fakes.FakeClock
import tech.atomiclabs.link.core.fakes.FakeLogger
import tech.atomiclabs.link.core.port.*

class AccessibilityGlobalKeysTest {
    @Test fun everyGlobalActionUsesTheActualPlatformIdAndChecksItsBoolean() = runTest {
        val raw = mockk<AccessibilityService>()
        val logger = FakeLogger(); val dispatcher = StandardTestDispatcher(testScheduler)
        val hub = AccessibilityHub(logger, FakeClock(testScheduler), dispatcher); hub.onConnected(raw)
        val keys = AccessibilityGlobalKeys(hub, logger, dispatcher)
        every { raw.performGlobalAction(any()) } returns true
        val ids = listOf(GlobalAction.BACK to AccessibilityService.GLOBAL_ACTION_BACK,
            GlobalAction.HOME to AccessibilityService.GLOBAL_ACTION_HOME, GlobalAction.RECENTS to AccessibilityService.GLOBAL_ACTION_RECENTS,
            GlobalAction.NOTIFICATIONS to AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS,
            GlobalAction.QUICK_SETTINGS to AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS,
            GlobalAction.POWER_DIALOG to AccessibilityService.GLOBAL_ACTION_POWER_DIALOG,
            GlobalAction.LOCK_SCREEN to AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
        assertEquals(GlobalAction.entries.toSet(), ids.map { it.first }.toSet())
        for ((action, id) in ids) {
            assertEquals(PortResult.Success(Unit), keys.perform(action))
            verify(exactly = 1) { raw.performGlobalAction(id) }
        }
        every { raw.performGlobalAction(any()) } returns false
        assertEquals(FailureKind.REJECTED, (keys.perform(GlobalAction.HOME) as PortResult.Failure).failure.kind)
    }

    @Test fun unavailableAndThrowingServiceRemainTypedAndSanitized() = runTest {
        val raw = mockk<AccessibilityService>()
        val logger = FakeLogger(); val dispatcher = StandardTestDispatcher(testScheduler)
        val hub = AccessibilityHub(logger, FakeClock(testScheduler), dispatcher)
        val keys = AccessibilityGlobalKeys(hub, logger, dispatcher)
        assertEquals(FailureKind.UNAVAILABLE, (keys.perform(GlobalAction.BACK) as PortResult.Failure).failure.kind)
        hub.onConnected(raw)
        val cause = SecurityException("private fixture payload")
        every { raw.performGlobalAction(any()) } throws cause
        val failure = (keys.perform(GlobalAction.BACK) as PortResult.Failure).failure
        assertEquals(FailureKind.PERMISSION_DENIED, failure.kind)
        assertTrue(generateSequence(failure.cause) { it.cause }.any { it === cause })
        assertFalse(logger.entries.any { it.cause?.stackTraceToString()?.contains("private fixture payload") == true })
    }
}
