package tech.atomiclabs.link.device.capture

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.view.Display
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlinx.coroutines.*
import tech.atomiclabs.link.core.command.FailureDiagnostics
import tech.atomiclabs.link.core.log.Logger
import tech.atomiclabs.link.core.port.*
import tech.atomiclabs.link.device.access.AccessibilityHub

class AccessibilityScreenshotSource(
    private val hub: AccessibilityHub,
    private val encoder: ImageEncoder,
    private val logger: Logger,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val callbackDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ScreenshotSource {
    override suspend fun capture(target: CaptureTarget): ScreenshotOutcome {
        val current = hub.currentService()
        if (current is PortResult.Failure) return ScreenshotOutcome.Failed(current.failure)
        val service = (current as PortResult.Success).value
        return try {
            withTimeout(5_000) { withContext(dispatcher) {
                suspendCancellableCoroutine { continuation ->
                    val delivered = AtomicBoolean()
                    val callback = object : AccessibilityService.TakeScreenshotCallback {
                        override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                            val outcome = try {
                                val image = result.hardwareBuffer.use { buffer ->
                                    if (!continuation.isActive) {
                                        logger.warn("AccessibilityScreenshotSource", "onSuccess", "state=late_callback")
                                        return
                                    }
                                    val hardware = checkNotNull(Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)) {
                                        "The screenshot buffer could not be wrapped."
                                    }
                                    copyPixels(hardware)
                                }
                                ScreenshotOutcome.Captured(image)
                            } catch (cause: Exception) {
                                failure("convert", target, cause, FailureKind.PLATFORM)
                            }
                            deliver(continuation, delivered, outcome)
                        }

                        override fun onFailure(errorCode: Int) {
                            val outcome = when (errorCode) {
                                AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT -> {
                                    logger.warn("AccessibilityScreenshotSource", "capture", "platform_code=$errorCode")
                                    ScreenshotOutcome.RateLimited(errorCode)
                                }
                                AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW -> {
                                    logger.warn("AccessibilityScreenshotSource", "capture", "platform_code=$errorCode")
                                    ScreenshotOutcome.SecureWindow(errorCode)
                                }
                                else -> failure("capture", target, IllegalStateException("Screenshot platform refusal."),
                                    FailureKind.PLATFORM, errorCode)
                            }
                            deliver(continuation, delivered, outcome)
                        }
                    }
                    val executor = callbackDispatcher.asExecutor()
                    when (target) {
                        CaptureTarget.DefaultDisplay -> service.takeScreenshot(Display.DEFAULT_DISPLAY, executor, callback)
                        is CaptureTarget.Window -> service.takeScreenshotOfWindow(target.windowId, executor, callback)
                    }
                }
            } }
        } catch (cause: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            failure("capture", target, cause, FailureKind.TIMED_OUT)
        } catch (cause: CancellationException) {
            throw cause
        } catch (cause: Exception) {
            failure("capture", target, cause, if (cause is SecurityException) FailureKind.PERMISSION_DENIED else FailureKind.PLATFORM)
        }
    }

    override suspend fun encode(image: CapturedImage, format: ImageFormat, quality: Int): PortResult<EncodedImage> =
        encoder.encode(image, format, quality)

    private fun copyPixels(hardware: Bitmap): CapturedImage = try {
        val software = checkNotNull(hardware.copy(Bitmap.Config.ARGB_8888, false)) { "The screenshot copy was refused." }
        try {
            val width = software.width; val height = software.height
            require(width > 0 && height > 0 && width.toLong() * height <= Int.MAX_VALUE) { "Screenshot dimensions are invalid." }
            val pixels = IntArray(width * height)
            software.getPixels(pixels, 0, width, 0, 0, width, height)
            CapturedImage(width, height, pixels)
        } finally { software.recycle() }
    } finally { hardware.recycle() }

    private fun deliver(
        continuation: CancellableContinuation<ScreenshotOutcome>,
        delivered: AtomicBoolean,
        outcome: ScreenshotOutcome,
    ) {
        if (delivered.compareAndSet(false, true)) continuation.resume(outcome)
        else logger.warn("AccessibilityScreenshotSource", "deliver", "state=duplicate_callback")
    }

    private fun failure(operation: String, target: CaptureTarget, cause: Exception, kind: FailureKind,
        code: Int? = null): ScreenshotOutcome.Failed {
        val context = when (target) {
            CaptureTarget.DefaultDisplay -> "target=display state=failed"
            is CaptureTarget.Window -> "window_id=${target.windowId} state=failed"
        }
        logger.error("AccessibilityScreenshotSource", operation, context, FailureDiagnostics.sanitized(cause))
        return ScreenshotOutcome.Failed(PortFailure(kind, cause, platformCode = code))
    }
}
