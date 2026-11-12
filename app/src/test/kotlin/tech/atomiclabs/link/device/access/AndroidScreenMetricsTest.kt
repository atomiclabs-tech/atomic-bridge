package tech.atomiclabs.link.device.access

import android.hardware.display.DisplayManager
import android.util.DisplayMetrics as PlatformMetrics
import android.view.Display
import io.mockk.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import tech.atomiclabs.link.core.fakes.FakeLogger
import tech.atomiclabs.link.core.port.*

class AndroidScreenMetricsTest {
    @Suppress("DEPRECATION")
    @Test fun readsRealPixelsAndDensityFromTheActualDefaultDisplay() = runTest {
        val manager = mockk<DisplayManager>(); val display = mockk<Display>()
        every { manager.getDisplay(Display.DEFAULT_DISPLAY) } returns display
        every { display.getRealMetrics(any()) } answers {
            firstArg<PlatformMetrics>().apply { widthPixels = 1080; heightPixels = 2400; density = 3f }
        }
        assertEquals(PortResult.Success(DisplayMetrics(1080, 2400, 3f)),
            AndroidScreenMetrics(manager, FakeLogger(), StandardTestDispatcher(testScheduler)).read())
        verify(exactly = 1) { manager.getDisplay(Display.DEFAULT_DISPLAY); display.getRealMetrics(any()) }
    }

    @Suppress("DEPRECATION")
    @Test fun absentInvalidAndRefusedMetricsFailWithoutInventedDimensions() = runTest {
        val manager = mockk<DisplayManager>(); val display = mockk<Display>(); val logger = FakeLogger()
        val adapter = AndroidScreenMetrics(manager, logger, StandardTestDispatcher(testScheduler))
        every { manager.getDisplay(any()) } returns null
        assertTrue(adapter.read() is PortResult.Failure)
        every { manager.getDisplay(any()) } returns display
        every { display.getRealMetrics(any()) } just Runs
        assertTrue(adapter.read() is PortResult.Failure)
        val cause = SecurityException("private fixture payload")
        every { display.getRealMetrics(any()) } throws cause
        val failed = adapter.read() as PortResult.Failure
        assertEquals(FailureKind.PERMISSION_DENIED, failed.failure.kind)
        assertTrue(generateSequence(failed.failure.cause) { it.cause }.any { it === cause })
        assertEquals(3, logger.entries.size)
        assertFalse(logger.entries.any { it.cause!!.stackTraceToString().contains("private fixture payload") })
    }
}
