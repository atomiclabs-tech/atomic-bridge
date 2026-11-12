package tech.atomiclabs.link.core.capture

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import tech.atomiclabs.link.core.command.*
import tech.atomiclabs.link.core.log.Logger
import tech.atomiclabs.link.core.port.*
import tech.atomiclabs.link.core.screen.ScreenReadException
import tech.atomiclabs.link.core.screen.WindowOrder

class ScreenshotCommand(
    private val source: ScreenshotSource,
    private val media: MediaTransfer,
    private val windows: WindowOrder,
    private val clock: Clock,
    private val logger: Logger,
) {
    suspend fun execute(httpBase: String): CommandResult = try {
        val original = capture(CaptureTarget.DefaultDisplay)
        val snapshot = windows.snapshot()
        val application = snapshot.windows.firstOrNull { it.window?.type == WindowType.APPLICATION }
            ?: snapshot.windows.firstOrNull { it.window == null }
            ?: fail(ErrorCode.SCREENSHOT_FAILED, "The application window identity is unavailable.")
        clock.sleep(400)
        capture(CaptureTarget.Window(application.window?.id ?: application.properties.windowId))
        val png = encode(original, ImageFormat.PNG, 100)
        val encoded = if (png.bytes.size > 8 * 1024 * 1024) {
            logger.warn("ScreenshotCommand", "jpegFallback", "state=png_exceeds_limit")
            encode(original, ImageFormat.JPEG, 90)
        } else png
        if (httpBase.isBlank()) fail(ErrorCode.UPLOAD_FAILED, "The media upload address is missing.")
        val uploaded = when (val result = media.upload(httpBase, encoded)) {
            is PortResult.Success -> result.value
            is PortResult.Failure -> outsideFailure("upload", result.failure, ErrorCode.UPLOAD_FAILED)
        }
        if (uploaded.token.isBlank()) fail(ErrorCode.UPLOAD_FAILED, "The media upload returned no token.")
        CommandResult.Success(buildJsonObject {
            put("media", "MEDIA:hermes-relay://${uploaded.token}")
            put("width", encoded.width); put("height", encoded.height)
            put("bytes", encoded.bytes.size)
            put("format", if (encoded.format == ImageFormat.PNG) "png" else "jpeg")
        })
    } catch (cause: CaptureFailure) {
        cause.result
    } catch (cause: ScreenReadException) {
        logger.error("ScreenshotCommand", "windowRegion", "state=tree_failed", FailureDiagnostics.sanitized(cause))
        CommandResult.Failure(ErrorCode.SCREENSHOT_FAILED, "The active window could not be read.")
    }

    private suspend fun capture(target: CaptureTarget): CapturedImage {
        var result = source.capture(target)
        for (wait in listOf(400L, 1100L)) {
            if (result !is ScreenshotOutcome.RateLimited) break
            logger.warn("ScreenshotCommand", "captureRetry", "target=$target platform_code=${result.platformCode}")
            clock.sleep(wait)
            result = source.capture(target)
        }
        return when (result) {
            is ScreenshotOutcome.Captured -> result.image.also(::validate)
            is ScreenshotOutcome.RateLimited -> fail(ErrorCode.SCREENSHOT_RATE_LIMITED,
                "Screenshot capture remained rate limited with platform code ${result.platformCode}.")
            is ScreenshotOutcome.SecureWindow -> secure(result.platformCode)
            is ScreenshotOutcome.Failed -> outsideFailure("capture", result.failure, ErrorCode.SCREENSHOT_FAILED)
        }
    }

    private fun secure(code: Int): Nothing = fail(ErrorCode.SECURE_WINDOW,
        "The active window could not be captured with platform code $code.")

    private fun validate(image: CapturedImage) {
        if (image.width <= 0 || image.height <= 0 || image.width.toLong() * image.height != image.argb.size.toLong()) {
            fail(ErrorCode.SCREENSHOT_FAILED, "The screenshot pixels have invalid dimensions.")
        }
    }

    private suspend fun encode(image: CapturedImage, format: ImageFormat, quality: Int): EncodedImage {
        val encoded = when (val result = source.encode(image, format, quality)) {
            is PortResult.Success -> result.value
            is PortResult.Failure -> outsideFailure("encode", result.failure, ErrorCode.SCREENSHOT_FAILED)
        }
        if (encoded.width != image.width || encoded.height != image.height || encoded.format != format || encoded.bytes.isEmpty()) {
            fail(ErrorCode.SCREENSHOT_FAILED, "The encoded screenshot is invalid.")
        }
        return encoded
    }

    private fun outsideFailure(operation: String, failure: PortFailure, code: ErrorCode): Nothing {
        logger.error("ScreenshotCommand", operation, "state=failed", FailureDiagnostics.sanitized(failure.cause))
        val message = when {
            failure.httpStatus != null -> "Screenshot upload failed with HTTP status ${failure.httpStatus}."
            operation == "capture" && failure.platformCode != null -> "Screenshot capture failed with platform code ${failure.platformCode}."
            operation == "capture" && failure.kind == FailureKind.TIMED_OUT -> "Screenshot capture did not complete within five seconds."
            else -> "Screenshot $operation failed because of a ${failure.kind.name.lowercase()} error."
        }
        throw CaptureFailure(CommandResult.Failure(code, message), failure.cause)
    }

    private fun fail(code: ErrorCode, message: String): Nothing {
        logger.warn("ScreenshotCommand", "execute", "state=${code.wireValue}")
        throw CaptureFailure(CommandResult.Failure(code, message))
    }

    private class CaptureFailure(val result: CommandResult.Failure, cause: Throwable? = null) :
        IllegalStateException("Screenshot command failed.", cause)
}
