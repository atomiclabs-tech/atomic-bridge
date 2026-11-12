package tech.atomiclabs.link.device.capture

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.*
import tech.atomiclabs.link.core.command.FailureDiagnostics
import tech.atomiclabs.link.core.log.Logger
import tech.atomiclabs.link.core.port.*

class ImageEncoder(
    private val logger: Logger,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    suspend fun encode(image: CapturedImage, format: ImageFormat, quality: Int): PortResult<EncodedImage> = try {
        PortResult.Success(withTimeout(5_000) { withContext(dispatcher) {
            require(image.width > 0 && image.height > 0 && image.width.toLong() * image.height == image.argb.size.toLong()) {
                "Screenshot dimensions are invalid."
            }
            require(quality in 0..100) { "Screenshot encoding quality is invalid." }
            val bitmap = Bitmap.createBitmap(image.argb, image.width, image.height, Bitmap.Config.ARGB_8888)
            try {
                ByteArrayOutputStream().use { output ->
                    val compression = if (format == ImageFormat.PNG) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
                    check(bitmap.compress(compression, quality, output)) { "Screenshot compression was refused." }
                    val bytes = output.toByteArray()
                    check(bytes.isNotEmpty()) { "Screenshot compression produced no bytes." }
                    EncodedImage(image.width, image.height, format, bytes)
                }
            } finally { bitmap.recycle() }
        } })
    } catch (cause: TimeoutCancellationException) {
        currentCoroutineContext().ensureActive()
        failure(cause, FailureKind.TIMED_OUT)
    } catch (cause: CancellationException) {
        throw cause
    } catch (cause: Exception) {
        failure(cause, if (cause is IllegalArgumentException) FailureKind.INVALID_DATA else FailureKind.PLATFORM)
    }

    private fun failure(cause: Exception, kind: FailureKind): PortResult.Failure {
        logger.error("ImageEncoder", "encode", "state=failed", FailureDiagnostics.sanitized(cause))
        return PortResult.Failure(PortFailure(kind, cause))
    }
}
