package tech.atomiclabs.link.core.capture

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import tech.atomiclabs.link.core.command.*
import tech.atomiclabs.link.core.fakes.*
import tech.atomiclabs.link.core.port.*
import tech.atomiclabs.link.core.screen.WindowOrder

internal class CaptureFake : ScreenshotSource {
    val image = CapturedImage(4, 4, IntArray(16) { -1 })
    val targets = mutableListOf<CaptureTarget>()
    val outcomes = ArrayDeque<ScreenshotOutcome>()
    val windowOutcomes = ArrayDeque<ScreenshotOutcome>()
    val encodings = mutableListOf<Triple<CapturedImage, ImageFormat, Int>>()
    var pngSize = 5
    var encodeFailure: PortFailure? = null
    override suspend fun capture(target: CaptureTarget): ScreenshotOutcome {
        targets += target
        val queue = if (target is CaptureTarget.Window) windowOutcomes else outcomes
        return if (queue.isEmpty()) ScreenshotOutcome.Captured(image) else queue.removeFirst()
    }
    override suspend fun encode(image: CapturedImage, format: ImageFormat, quality: Int): PortResult<EncodedImage> {
        encodings += Triple(image, format, quality)
        return encodeFailure?.let { PortResult.Failure(it) } ?: PortResult.Success(EncodedImage(
            image.width, image.height, format, ByteArray(if (format == ImageFormat.PNG) pngSize else 4)))
    }
}

internal class MediaFake : MediaTransfer {
    val uploads = mutableListOf<Pair<String, EncodedImage>>()
    var result: PortResult<UploadedMedia> = PortResult.Success(UploadedMedia("fixture"))
    override suspend fun upload(httpBase: String, image: EncodedImage): PortResult<UploadedMedia> {
        uploads += httpBase to image
        return result
    }
    override suspend fun fetch(httpBase: String, token: String): PortResult<FetchedMedia> = error("No media fetch in capture")
}

internal fun applicationTree(
    bounds: Bounds = Bounds(1, 1, 3, 3), windowBounds: Bounds = bounds, rootWindowId: Int = 47,
): FakeUiTree {
    val app = FakeUiNode(UiNodeProperties(bounds, windowId = rootWindowId, packageName = "example.app"))
    val bars = FakeUiNode(UiNodeProperties(Bounds(0, 0, 4, 1), windowId = 9, packageName = "com.android.systemui"))
    return FakeUiTree().apply { windowResult = PortResult.Success(listOf(
        UiWindow(9, WindowType.SYSTEM, 3, 99, true, true, bars, Bounds(0, 0, 4, 1)),
        UiWindow(47, WindowType.APPLICATION, 1, 1, true, false, app, windowBounds))) }
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ScreenshotCommandTest {
    private val source = CaptureFake()
    private val media = MediaFake()
    private val tree = applicationTree()
    private val logger = FakeLogger()
    private fun TestScope.command() = ScreenshotCommand(source, media, WindowOrder(tree, logger), FakeClock(testScheduler), logger)
    private fun failure(result: CommandResult, code: ErrorCode): CommandResult.Failure {
        assertTrue(result is CommandResult.Failure)
        val failed = result as CommandResult.Failure
        assertEquals(code, failed.code)
        assertFalse(failed.toJson().containsKey("media"))
        return failed
    }

    @Test fun unreportedNavigationPillCannotBypassTheAuthoritativeWindowRefusal() = runTest {
        tree.windowResult = applicationTree(Bounds(0, 0, 4, 4), Bounds(0, 0, 4, 4), rootWindowId = 83).windowResult
        val display = source.image.copy(argb = IntArray(16) { if (it < 4 || it == 14) -1 else 0xff000000.toInt() })
        source.outcomes += ScreenshotOutcome.Captured(display)
        source.windowOutcomes += ScreenshotOutcome.SecureWindow(6)
        failure(command().execute("configured-base"), ErrorCode.SECURE_WINDOW)
        assertEquals(listOf(CaptureTarget.DefaultDisplay, CaptureTarget.Window(47)), source.targets)
        assertEquals(400, currentTime)
        assertTrue(source.encodings.isEmpty()); assertTrue(media.uploads.isEmpty())
    }

    @Test fun genuineDarkAndAllBlackCapturedWindowsRemainSuccessfulAndKeepTheDisplayFrame() = runTest {
        for (toolbar in listOf(false, true)) {
            val display = source.image.copy(argb = IntArray(16) { if (toolbar && it < 4) -1 else 0xff000000.toInt() })
            val saved = display.argb.copyOf()
            source.outcomes += ScreenshotOutcome.Captured(display)
            source.windowOutcomes += ScreenshotOutcome.Captured(CapturedImage(2, 2, IntArray(4)))
            assertTrue(command().execute("configured-base") is CommandResult.Success)
            assertEquals(listOf(CaptureTarget.DefaultDisplay, CaptureTarget.Window(47)), source.targets)
            assertSame(display, source.encodings.single().first)
            assertEquals(4, media.uploads.single().second.width)
            assertArrayEquals(saved, display.argb)
            source.targets.clear(); source.encodings.clear(); media.uploads.clear()
        }
    }

    @Test fun aWindowPlatformFailureIsNotInventedAsASecureRefusal() = runTest {
        source.outcomes += ScreenshotOutcome.Captured(source.image.copy(argb = IntArray(16)))
        source.windowOutcomes += ScreenshotOutcome.Failed(PortFailure(FailureKind.PLATFORM,
            IllegalStateException("private-window-data"), platformCode = 5))
        val failed = failure(command().execute("configured-base"), ErrorCode.SCREENSHOT_FAILED)
        assertTrue(failed.error.contains("5"))
        assertTrue(source.encodings.isEmpty()); assertTrue(media.uploads.isEmpty())
        assertFalse(logger.entries.any { it.cause?.stackTraceToString()?.contains("private-window-data") == true })
    }

    @Test fun windowRateRefusalsExhaustTwoRetriesAsRateLimitedRatherThanSecure() = runTest {
        source.outcomes += ScreenshotOutcome.Captured(source.image.copy(argb = IntArray(16)))
        repeat(3) { source.windowOutcomes += ScreenshotOutcome.RateLimited(3) }
        failure(command().execute("configured-base"), ErrorCode.SCREENSHOT_RATE_LIMITED)
        assertEquals(listOf(CaptureTarget.DefaultDisplay) + List(3) { CaptureTarget.Window(47) }, source.targets)
        assertEquals(1900, currentTime)
        assertTrue(source.encodings.isEmpty()); assertTrue(media.uploads.isEmpty())
    }

    @Test fun successUploadsFullSizePngAndReturnsExactlyTheMediaShape() = runTest {
        val result = command().execute("configured-base") as CommandResult.Success
        assertEquals(setOf("media", "width", "height", "bytes", "format"), result.fields.keys)
        assertEquals("MEDIA:hermes-relay://fixture", result.fields["media"]!!.jsonPrimitive.content)
        assertEquals(4, result.fields["width"]!!.jsonPrimitive.int)
        assertEquals(4, result.fields["height"]!!.jsonPrimitive.int)
        assertEquals(5, result.fields["bytes"]!!.jsonPrimitive.int)
        assertEquals("png", result.fields["format"]!!.jsonPrimitive.content)
        assertEquals("configured-base", media.uploads.single().first)
        assertEquals(listOf(CaptureTarget.DefaultDisplay, CaptureTarget.Window(47)), source.targets)
        assertEquals(100, source.encodings.single().third)
        assertEquals(400, currentTime)
    }

    @Test fun intervalRefusalsRetry400Then1100msOnlyTwice() = runTest {
        repeat(2) { source.outcomes += ScreenshotOutcome.RateLimited(3) }
        assertTrue(command().execute("configured-base") is CommandResult.Success)
        assertEquals(1_900, currentTime)
        assertEquals(List(3) { CaptureTarget.DefaultDisplay } + CaptureTarget.Window(47), source.targets)
        source.targets.clear()
        repeat(3) { source.outcomes += ScreenshotOutcome.RateLimited(3) }
        failure(command().execute("configured-base"), ErrorCode.SCREENSHOT_RATE_LIMITED)
        assertEquals(3, source.targets.size)
        assertEquals(3_400, currentTime)
        assertEquals(1, media.uploads.size)
    }

    @Test fun platformTimeoutEncodeAndUploadFailuresNeverReturnAMarkerAndKeepPublicCodes() = runTest {
        source.outcomes += ScreenshotOutcome.Failed(PortFailure(FailureKind.PLATFORM, IllegalStateException("private-image"), platformCode = 4))
        assertTrue(failure(command().execute("configured-base"), ErrorCode.SCREENSHOT_FAILED).error.contains("4"))
        source.outcomes += ScreenshotOutcome.Failed(PortFailure(FailureKind.TIMED_OUT, IllegalStateException("private-image")))
        assertTrue(failure(command().execute("configured-base"), ErrorCode.SCREENSHOT_FAILED).error.contains("five seconds"))
        source.encodeFailure = PortFailure(FailureKind.PLATFORM, IllegalStateException("private-image"))
        failure(command().execute("configured-base"), ErrorCode.SCREENSHOT_FAILED)
        source.encodeFailure = null
        media.result = PortResult.Failure(PortFailure(FailureKind.IO, IllegalStateException("private-media"), httpStatus = 503))
        assertTrue(failure(command().execute("configured-base"), ErrorCode.UPLOAD_FAILED).error.contains("503"))
        assertFalse(logger.entries.any { it.cause?.stackTraceToString()?.contains("private-") == true })
    }

    @Test fun edgeToEdgeBlackApplicationWithVisibleSystemBarReturnsSecureWithoutUpload() = runTest {
        tree.windowResult = applicationTree(Bounds(0, 0, 4, 4), Bounds(0, 0, 4, 4), rootWindowId = 83).windowResult
        val display = source.image.copy(argb = IntArray(16) { if (it < 4) -1 else 0xff000000.toInt() })
        source.outcomes += ScreenshotOutcome.Captured(display)
        source.windowOutcomes += ScreenshotOutcome.SecureWindow(6)
        failure(command().execute("configured-base"), ErrorCode.SECURE_WINDOW)
        assertEquals(listOf(CaptureTarget.DefaultDisplay, CaptureTarget.Window(47)), source.targets)
        assertEquals(400, currentTime)
        assertTrue(source.encodings.isEmpty()); assertTrue(media.uploads.isEmpty())
    }

    @Test fun invalidWindowConfirmationPixelsRemainAnExplicitScreenshotFailure() = runTest {
        source.outcomes += ScreenshotOutcome.Captured(source.image.copy(argb = IntArray(16)))
        source.windowOutcomes += ScreenshotOutcome.Captured(CapturedImage(2, 2, intArrayOf(0)))
        failure(command().execute("configured-base"), ErrorCode.SCREENSHOT_FAILED)
        assertTrue(source.encodings.isEmpty()); assertTrue(media.uploads.isEmpty())
    }

    @Test fun systemCoverageDoesNotOverrideASuccessfulWindowOutcome() = runTest {
        val actual = (tree.windowResult as PortResult.Success).value
        tree.windowResult = PortResult.Success(actual.map { window ->
            if (window.type == WindowType.SYSTEM) window.copy(bounds = Bounds(0, 0, 4, 4)) else window
        })
        assertTrue(command().execute("configured-base") is CommandResult.Success)
        assertEquals(listOf(CaptureTarget.DefaultDisplay, CaptureTarget.Window(47)), source.targets)
        assertSame(source.image, source.encodings.single().first)
    }

    @Test fun lowerLayerSystemWindowsDoNotHideRealNonblackApplicationPixels() = runTest {
        val actual = (tree.windowResult as PortResult.Success).value
        tree.windowResult = PortResult.Success(actual.map { window ->
            if (window.type == WindowType.SYSTEM) window.copy(layer = 0, bounds = Bounds(0, 0, 4, 4)) else window
        })
        assertTrue(command().execute("configured-base") is CommandResult.Success)
        assertEquals(listOf(CaptureTarget.DefaultDisplay, CaptureTarget.Window(47)), source.targets)
        assertSame(source.image, source.encodings.single().first)
        assertEquals(400, currentTime)
    }

    @Test fun coloredDisplayDoesNotSuppressTheAuthoritativeSecureWindowCheck() = runTest {
        tree.windowResult = applicationTree(Bounds(1, 1, 2, 2), Bounds(1, 1, 3, 3)).windowResult
        source.outcomes += ScreenshotOutcome.Captured(source.image.copy(argb = IntArray(16) { if (it == 5) 0 else -1 }))
        source.windowOutcomes += ScreenshotOutcome.SecureWindow(6)
        failure(command().execute("configured-base"), ErrorCode.SECURE_WINDOW)
        assertEquals(listOf(CaptureTarget.DefaultDisplay, CaptureTarget.Window(47)), source.targets)
        assertTrue(source.encodings.isEmpty()); assertTrue(media.uploads.isEmpty())
    }

    @Test fun actualApplicationIdIsUsedInsteadOfItsDifferentRootId() = runTest {
        tree.windowResult = applicationTree(Bounds(0, 0, 4, 4), Bounds(1, 1, 3, 3), rootWindowId = 83).windowResult
        val blackWindow = source.image.copy(argb = IntArray(16) { if (it / 4 in 1..2 && it % 4 in 1..2) 0 else -1 })
        source.outcomes += ScreenshotOutcome.Captured(blackWindow)
        source.windowOutcomes += ScreenshotOutcome.Captured(source.image)
        assertTrue(command().execute("configured-base") is CommandResult.Success)
        assertEquals(listOf(CaptureTarget.DefaultDisplay, CaptureTarget.Window(47)), source.targets)
        assertEquals(400, currentTime)
        assertSame(blackWindow, source.encodings.single().first)
    }

    @Test fun activeRootFallbackUsesItsRealWindowIdWithoutInventingWindowMetadata() = runTest {
        source.outcomes += ScreenshotOutcome.Captured(source.image.copy(argb = IntArray(16) { if (it < 4) 0 else -1 }))
        assertTrue(command().execute("configured-base") is CommandResult.Success)
        assertEquals(listOf(CaptureTarget.DefaultDisplay, CaptureTarget.Window(47)), source.targets)
        tree.windowResult = PortResult.Success(emptyList())
        tree.root = ReadValue.Present(FakeUiNode(UiNodeProperties(Bounds(1, 1, 2, 2), windowId = 83)))
        source.outcomes += ScreenshotOutcome.Captured(source.image.copy(argb = IntArray(16) { if (it == 5) 0 else -1 }))
        source.windowOutcomes += ScreenshotOutcome.Captured(source.image)
        assertTrue(command().execute("configured-base") is CommandResult.Success)
        assertEquals(CaptureTarget.Window(83), source.targets.last())
    }

    @Test fun windowRateRetryCanRecoverAndKeepsTheFullDisplayFrame() = runTest {
        repeat(2) { source.windowOutcomes += ScreenshotOutcome.RateLimited(3) }
        assertTrue(command().execute("configured-base") is CommandResult.Success)
        assertEquals(listOf(CaptureTarget.DefaultDisplay) + List(3) { CaptureTarget.Window(47) }, source.targets)
        assertEquals(1900, currentTime)
        assertSame(source.image, source.encodings.single().first)
    }

    @Test fun cancellationDuringTheCaptureIntervalNeverUploadsTheUncheckedFrame() = runTest {
        val pending = async { command().execute("configured-base") }
        runCurrent()
        assertFalse(pending.isCompleted)
        pending.cancelAndJoin()
        assertTrue(pending.isCancelled)
        assertEquals(listOf(CaptureTarget.DefaultDisplay), source.targets)
        assertTrue(source.encodings.isEmpty()); assertTrue(media.uploads.isEmpty())
    }

    @Test fun missingOrUnreadableRealWindowIdentityFailsWithoutEncodingOrUpload() = runTest {
        tree.windowResult = PortResult.Success(emptyList())
        failure(command().execute("configured-base"), ErrorCode.SCREENSHOT_FAILED)
        assertEquals(listOf(CaptureTarget.DefaultDisplay), source.targets)
        tree.windowResult = PortResult.Failure(PortFailure(FailureKind.PLATFORM, IllegalStateException("private-window")))
        failure(command().execute("configured-base"), ErrorCode.SCREENSHOT_FAILED)
        assertEquals(List(2) { CaptureTarget.DefaultDisplay }, source.targets)
        assertTrue(source.encodings.isEmpty()); assertTrue(media.uploads.isEmpty())
        assertFalse(logger.entries.any { it.cause?.stackTraceToString()?.contains("private-window") == true })
    }

    @Test fun oversizedPngFallsBackToFullSizeJpeg90WithWarningAndExactlyEightMiBDoesNot() = runTest {
        source.pngSize = 8 * 1024 * 1024 + 1
        val result = command().execute("configured-base") as CommandResult.Success
        assertEquals("jpeg", result.fields["format"]!!.jsonPrimitive.content)
        assertEquals(ImageFormat.JPEG, media.uploads.single().second.format)
        assertEquals(listOf(ImageFormat.PNG, ImageFormat.JPEG), source.encodings.map { it.second })
        assertEquals(90, source.encodings.last().third)
        assertTrue(logger.entries.any { it.level == "warn" && it.operation == "jpegFallback" })
        source.encodings.clear(); source.pngSize = 8 * 1024 * 1024
        command().execute("configured-base")
        assertEquals(listOf(ImageFormat.PNG), source.encodings.map { it.second })
    }

    @Test fun invalidPixelsOrMissingUploadTokenCannotBecomeFakeSuccess() = runTest {
        source.outcomes += ScreenshotOutcome.Captured(CapturedImage(2, 2, intArrayOf(1)))
        failure(command().execute("configured-base"), ErrorCode.SCREENSHOT_FAILED)
        media.result = PortResult.Success(UploadedMedia(""))
        failure(command().execute("configured-base"), ErrorCode.UPLOAD_FAILED)
        assertTrue(logger.entries.any { it.level == "warn" })
    }
}
