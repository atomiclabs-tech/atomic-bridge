package tech.atomiclabs.link.device.access

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import io.mockk.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import tech.atomiclabs.link.core.fakes.FakeLogger
import tech.atomiclabs.link.core.port.*

class AndroidUiWindowTest {
    @Test fun mapsPlatformMetadataTypesAndAbsentRootsWithoutFabricatingContent() = runTest {
        val raw = mockk<AccessibilityWindowInfo>(relaxed = true)
        val root = mockk<AccessibilityNodeInfo>(relaxed = true)
        every { raw.id } returns 42
        every { raw.layer } returns 3
        every { raw.isActive } returns true
        every { raw.isFocused } returns true
        every { raw.root } returns root
        every { raw.getBoundsInScreen(any()) } answers {
            firstArg<Rect>().apply { left = -10; top = 20; right = 300; bottom = 600 }
        }
        every { root.getBoundsInScreen(any()) } answers {
            firstArg<Rect>().apply { left = 2; top = 30; right = 250; bottom = 500 }
        }
        val types = listOf(1 to WindowType.APPLICATION, 2 to WindowType.INPUT_METHOD, 3 to WindowType.SYSTEM,
            4 to WindowType.ACCESSIBILITY_OVERLAY, 5 to WindowType.SPLIT_SCREEN_DIVIDER, 99 to WindowType.OTHER)
        for ((platform, expected) in types) {
            every { raw.type } returns platform
            val result = (AndroidUiWindow(raw, FakeLogger(), StandardTestDispatcher(testScheduler)).snapshot()
                as PortResult.Success).value
            assertEquals(expected, result.type)
            assertEquals(platform, result.platformType)
            assertEquals(42, result.id)
            assertEquals(3, result.layer)
            assertTrue(result.active && result.focused)
            assertNotNull(result.root)
            assertEquals(Bounds(-10, 20, 300, 600), result.bounds)
            assertEquals(Bounds(2, 30, 250, 500), (result.root!!.read() as PortResult.Success).value.bounds)
        }
        every { raw.root } returns null
        val absentRoot = (AndroidUiWindow(raw, FakeLogger(), StandardTestDispatcher(testScheduler)).snapshot()
            as PortResult.Success).value
        assertNull(absentRoot.root)
        assertEquals(Bounds(-10, 20, 300, 600), absentRoot.bounds)
    }

    @Test fun throwingRootRetainsCauseAndLogsWindowIdWithoutRawMessages() = runTest {
        val raw = mockk<AccessibilityWindowInfo>(relaxed = true)
        every { raw.id } returns 51
        val cause = IllegalStateException("private-window-content")
        every { raw.root } throws cause
        val logger = FakeLogger()
        val result = AndroidUiWindow(raw, logger, StandardTestDispatcher(testScheduler)).snapshot() as PortResult.Failure
        assertTrue(generateSequence(result.failure.cause) { it.cause }.any { it === cause })
        assertTrue(logger.entries.single().context.contains("window_id=51"))
        assertFalse(logger.entries.single().cause!!.stackTraceToString().contains("private-window-content"))
    }

    @Test fun throwingGeometryReturnsTypedFailureWithCauseAndSanitizedLogInsteadOfBounds() = runTest {
        val raw = mockk<AccessibilityWindowInfo>(relaxed = true)
        every { raw.id } returns 52
        val logger = FakeLogger()
        for (cause in listOf(SecurityException("private-window-geometry"), IllegalStateException("private-window-geometry"))) {
            every { raw.getBoundsInScreen(any()) } throws cause
            val result = AndroidUiWindow(raw, logger, StandardTestDispatcher(testScheduler)).snapshot() as PortResult.Failure
            assertEquals(if (cause is SecurityException) FailureKind.PERMISSION_DENIED else FailureKind.PLATFORM,
                result.failure.kind)
            assertTrue(generateSequence(result.failure.cause) { it.cause }.any { it === cause })
        }
        assertEquals(2, logger.entries.size)
        for (entry in logger.entries) {
            assertEquals("error", entry.level)
            assertEquals("AndroidUiWindow", entry.component)
            assertEquals("snapshot", entry.operation)
            assertTrue(entry.context.contains("window_id=52"))
            assertFalse(entry.cause!!.stackTraceToString().contains("private-window-geometry"))
            assertFalse(entry.context.contains("private-window-geometry"))
        }
    }
}
