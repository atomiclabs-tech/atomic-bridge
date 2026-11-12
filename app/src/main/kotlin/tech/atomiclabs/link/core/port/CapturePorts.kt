package tech.atomiclabs.link.core.port

/** Captures within 5 s, closes platform buffers, and exposes software ARGB pixels or a typed refusal. */
interface ScreenshotSource {
    suspend fun capture(target: CaptureTarget): ScreenshotOutcome
    suspend fun encode(image: CapturedImage, format: ImageFormat, quality: Int): PortResult<EncodedImage>
}

/** Authenticated HTTP: upload 15 s; fetch 20 s/25 MiB. Errors include HTTP status; tokens are never logged. */
interface MediaTransfer {
    suspend fun upload(httpBase: String, image: EncodedImage): PortResult<UploadedMedia>
    suspend fun fetch(httpBase: String, token: String): PortResult<FetchedMedia> // Enforces a 20 s timeout and 25 MiB cap.
    /** Consumes/closes source, returning size and SHA-256 computed over the streamed bytes. */
    suspend fun uploadFile(httpBase: String, source: FileReadHandle, contentType: String, name: String): PortResult<UploadedFile> {
        source.close()
        return PortResult.Failure(PortFailure(FailureKind.UNAVAILABLE, UnsupportedOperationException("File upload is unavailable.")))
    }
    /** Consumes/closes target and discards it on failure/cancellation. Enforces the lesser of maxBytes and 256 MiB. */
    suspend fun fetchToFile(httpBase: String, token: String, target: FileWriteHandle, maxBytes: Long): PortResult<FileTransferInfo> {
        target.discard()
        return PortResult.Failure(PortFailure(FailureKind.UNAVAILABLE, UnsupportedOperationException("File download is unavailable.")))
    }
}

sealed interface CaptureTarget {
    data object DefaultDisplay : CaptureTarget
    data class Window(val windowId: Int) : CaptureTarget
}

sealed interface ScreenshotOutcome {
    data class Captured(val image: CapturedImage) : ScreenshotOutcome
    data class RateLimited(val platformCode: Int) : ScreenshotOutcome
    data class SecureWindow(val platformCode: Int) : ScreenshotOutcome
    data class Failed(val failure: PortFailure) : ScreenshotOutcome
}

data class CapturedImage(val width: Int, val height: Int, val argb: IntArray)
data class EncodedImage(val width: Int, val height: Int, val format: ImageFormat, val bytes: ByteArray)
enum class ImageFormat { PNG, JPEG }
data class UploadedMedia(val token: String)
data class FetchedMedia(val bytes: ByteArray, val contentType: String?)
