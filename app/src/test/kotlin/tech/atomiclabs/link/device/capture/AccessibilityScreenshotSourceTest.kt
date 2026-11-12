package tech.atomiclabs.link.device.capture

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.hardware.HardwareBuffer
import android.view.Display
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import tech.atomiclabs.link.core.capture.MediaFake
import tech.atomiclabs.link.core.capture.ScreenshotCommand
import tech.atomiclabs.link.core.command.CommandResult
import tech.atomiclabs.link.core.command.ErrorCode
import tech.atomiclabs.link.core.fakes.FakeClock
import tech.atomiclabs.link.core.fakes.FakeLogger
import tech.atomiclabs.link.core.fakes.FakeUiTree
import tech.atomiclabs.link.core.port.*
import tech.atomiclabs.link.core.screen.WindowOrder
import tech.atomiclabs.link.device.access.AccessibilityHub

@OptIn(ExperimentalCoroutinesApi::class)
class AccessibilityScreenshotSourceTest {
    private val service = mockk<AccessibilityService>()
    private val logger = FakeLogger()
    private val callback = slot<AccessibilityService.TakeScreenshotCallback>()
    private val buffer = mockk<HardwareBuffer>(relaxed = true)
    private val hardware = mockk<Bitmap>(relaxed = true)
    private val software = mockk<Bitmap>(relaxed = true)
    private val result = mockk<AccessibilityService.ScreenshotResult>()
    private fun TestScope.source(connected: Boolean = true): AccessibilityScreenshotSource {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val hub = AccessibilityHub(logger, FakeClock(testScheduler), dispatcher)
        if (connected) hub.onConnected(service)
        every { service.takeScreenshot(Display.DEFAULT_DISPLAY, any(), capture(callback)) } just Runs
        every { service.takeScreenshotOfWindow(any(), any(), capture(callback)) } just Runs
        return AccessibilityScreenshotSource(hub, ImageEncoder(logger, dispatcher), logger, dispatcher, dispatcher)
    }
    private fun bitmaps() {
        mockkStatic(Bitmap::class)
        every { result.hardwareBuffer } returns buffer
        every { result.colorSpace } returns mockk<ColorSpace>()
        every { Bitmap.wrapHardwareBuffer(buffer, any()) } returns hardware
        every { hardware.copy(Bitmap.Config.ARGB_8888, false) } returns software
        every { software.width } returns 2
        every { software.height } returns 2
        every { software.getPixels(any(), 0, 2, 0, 0, 2, 2) } answers {
            firstArg<IntArray>().fill(0xff00ff00.toInt())
        }
    }

    @Test fun missingPlatformCallbackBecomesScreenshotFailedAtFiveSecondsWithoutUpload() = runTest {
        val media = MediaFake()
        val command = ScreenshotCommand(source(), media, WindowOrder(FakeUiTree(), logger), FakeClock(testScheduler), logger)
        val result = command.execute("configured-base") as CommandResult.Failure
        assertEquals(ErrorCode.SCREENSHOT_FAILED, result.code)
        assertEquals(5_000, currentTime)
        assertFalse(result.toJson().containsKey("media"))
        assertTrue(media.uploads.isEmpty())
    }

    @Test fun defaultDisplayAndRealWindowCapturesCopySoftwarePixelsAndCloseHardwareBuffer() = runTest {
        bitmaps()
        try {
            val source = source()
            for (target in listOf(CaptureTarget.DefaultDisplay, CaptureTarget.Window(47))) {
                val capture = async { source.capture(target) }; runCurrent()
                callback.captured.onSuccess(result)
                val image = (capture.await() as ScreenshotOutcome.Captured).image
                assertEquals(2, image.width); assertEquals(2, image.height)
                assertArrayEquals(IntArray(4) { 0xff00ff00.toInt() }, image.argb)
            }
            verify(exactly = 1) { service.takeScreenshot(Display.DEFAULT_DISPLAY, any(), any()) }
            verify(exactly = 1) { service.takeScreenshotOfWindow(47, any(), any()) }
            verify(exactly = 2) { buffer.close(); hardware.recycle(); software.recycle() }
        } finally { unmockkStatic(Bitmap::class) }
    }

    @Test fun everyConversionFailureClosesBufferAndRetainsCauseWithoutPrivateMessages() = runTest {
        bitmaps()
        try {
            val source = source()
            every { Bitmap.wrapHardwareBuffer(buffer, any()) } returns null
            var capture = async { source.capture(CaptureTarget.DefaultDisplay) }; runCurrent()
            callback.captured.onSuccess(result)
            assertTrue(capture.await() is ScreenshotOutcome.Failed)
            every { Bitmap.wrapHardwareBuffer(buffer, any()) } returns hardware
            every { hardware.copy(Bitmap.Config.ARGB_8888, false) } returns null
            capture = async { source.capture(CaptureTarget.DefaultDisplay) }; runCurrent()
            callback.captured.onSuccess(result)
            assertTrue(capture.await() is ScreenshotOutcome.Failed)
            every { hardware.copy(Bitmap.Config.ARGB_8888, false) } returns software
            val cause = IllegalStateException("private-image-data")
            every { software.getPixels(any(), any(), any(), any(), any(), any(), any()) } throws cause
            capture = async { source.capture(CaptureTarget.DefaultDisplay) }; runCurrent()
            callback.captured.onSuccess(result)
            val failure = (capture.await() as ScreenshotOutcome.Failed).failure
            assertTrue(generateSequence(failure.cause) { it.cause }.any { it === cause })
            verify(exactly = 3) { buffer.close() }
            verify(exactly = 2) { hardware.recycle() }
            verify(exactly = 1) { software.recycle() }
            assertFalse(logger.entries.any { it.cause?.stackTraceToString()?.contains("private-image-data") == true })
        } finally { unmockkStatic(Bitmap::class) }
    }

    @Test fun timeoutAtFiveSecondsAndCancellationStillCloseAnyLateSuccessBuffer() = runTest {
        bitmaps()
        try {
            val source = source()
            val capture = async { source.capture(CaptureTarget.DefaultDisplay) }
            runCurrent(); advanceTimeBy(4_999); runCurrent()
            assertFalse(capture.isCompleted)
            advanceTimeBy(1); runCurrent()
            assertEquals(FailureKind.TIMED_OUT, (capture.await() as ScreenshotOutcome.Failed).failure.kind)
            assertEquals(5_000, currentTime)
            callback.captured.onSuccess(result)
            val cancelled = launch { source.capture(CaptureTarget.DefaultDisplay) }
            runCurrent(); cancelled.cancelAndJoin()
            callback.captured.onSuccess(result)
            verify(exactly = 2) { buffer.close() }
            verify(exactly = 0) { Bitmap.wrapHardwareBuffer(any(), any()) }
            assertEquals(2, logger.entries.count { it.operation == "onSuccess" && it.level == "warn" })
        } finally { unmockkStatic(Bitmap::class) }
    }

    @Test fun platformCodesServiceAbsenceAndLaterRequestRefusalRemainTypedFailures() = runTest {
        val source = source()
        for (code in listOf(3, 6, 4)) {
            val capture = async { source.capture(CaptureTarget.Window(47)) }; runCurrent()
            callback.captured.onFailure(code)
            val outcome = capture.await()
            when (code) {
                3 -> assertEquals(ScreenshotOutcome.RateLimited(3), outcome)
                6 -> assertEquals(ScreenshotOutcome.SecureWindow(6), outcome)
                4 -> assertEquals(4, (outcome as ScreenshotOutcome.Failed).failure.platformCode)
            }
        }
        assertEquals(FailureKind.UNAVAILABLE, (source(false).capture(CaptureTarget.DefaultDisplay) as ScreenshotOutcome.Failed).failure.kind)
        every { service.takeScreenshot(any(), any(), any()) } throws SecurityException("private-service-value")
        assertEquals(FailureKind.PERMISSION_DENIED, (source.capture(CaptureTarget.DefaultDisplay) as ScreenshotOutcome.Failed).failure.kind)
        assertFalse(logger.entries.any { it.cause?.stackTraceToString()?.contains("private-service-value") == true })
    }

    @Test fun bufferCloseFailureIsLoggedAndReturnedInsteadOfAPartialImage() = runTest {
        bitmaps()
        try {
            every { buffer.close() } throws IllegalStateException("close refused")
            val source = source()
            val capture = async { source.capture(CaptureTarget.DefaultDisplay) }; runCurrent()
            callback.captured.onSuccess(result)
            assertTrue(capture.await() is ScreenshotOutcome.Failed)
            verify(exactly = 1) { buffer.close() }
            assertTrue(logger.entries.any { it.level == "error" && it.operation == "convert" })
        } finally { unmockkStatic(Bitmap::class) }
    }
}
