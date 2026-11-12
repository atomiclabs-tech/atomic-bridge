package tech.atomiclabs.link.core.files

import kotlinx.coroutines.*
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import tech.atomiclabs.link.core.command.*
import tech.atomiclabs.link.core.port.*

internal class FilePull(
    private val files: FileAccess,
    private val media: MediaTransfer,
    private val operations: FileOperations,
    private val ioDispatcher: CoroutineDispatcher,
    private val httpBase: suspend () -> String,
) {
    suspend fun execute(body: BodyParams): CommandResult {
        val path = operations.file("resolve", files.resolve(body.requiredString("path")))
        val entry = operations.file("stat", files.stat(path))
        if (entry.kind != FileKind.FILE) throw BadRequestException("path")
        if (entry.size > FilePaths.MAX_BYTES) operations.file("stat", FileResult.Failure(FileFailureKind.TOO_LARGE,
            PortFailure(FailureKind.REJECTED, IllegalArgumentException("The file exceeds 256 MiB."))))
        val endpoint = operations.endpoint(httpBase, ErrorCode.UPLOAD_FAILED)
        val contentType = contentType(entry.name)
        val source = OwnedReadHandle(operations.file("open", files.open(path)))
        val uploaded = try {
            operations.transfer("upload", ErrorCode.UPLOAD_FAILED) {
                media.uploadFile(endpoint, source, contentType, entry.name)
            }
        } finally { withContext(NonCancellable + ioDispatcher) { source.close() } }
        if (uploaded.token.isBlank() || uploaded.info.size != entry.size || !SHA256.matches(uploaded.info.sha256)) {
            operations.portFailure("upload", PortFailure(FailureKind.INVALID_DATA,
                IllegalStateException("The upload metadata is invalid.")), ErrorCode.UPLOAD_FAILED)
        }
        return CommandResult.Success(buildJsonObject {
            put("media", "MEDIA:hermes-relay://${uploaded.token}"); put("name", entry.name)
            put("size", uploaded.info.size); put("sha256", uploaded.info.sha256.lowercase())
            put("content_type", contentType)
        })
    }

    companion object {
        val SHA256 = Regex("[a-fA-F0-9]{64}")
        fun contentType(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "mp4" -> "video/mp4"
            "mov" -> "video/quicktime"
            "mp3" -> "audio/mpeg"
            "m4a" -> "audio/mp4"
            "pdf" -> "application/pdf"
            "txt" -> "text/plain"
            "json" -> "application/json"
            "zip" -> "application/zip"
            else -> "application/octet-stream"
        }
    }

    // Media owns the close normally; cancellation can win before it acquires the source.
    private class OwnedReadHandle(private val source: FileReadHandle) : FileReadHandle by source {
        private var closed = false
        @Synchronized override fun close() {
            if (!closed) { closed = true; source.close() }
        }
    }
}
