package tech.atomiclabs.link.core.files

import kotlinx.coroutines.*
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import tech.atomiclabs.link.core.command.*
import tech.atomiclabs.link.core.log.Logger
import tech.atomiclabs.link.core.port.*

internal class FilePush(
    private val files: FileAccess,
    private val media: MediaTransfer,
    private val operations: FileOperations,
    private val logger: Logger,
    private val ioDispatcher: CoroutineDispatcher,
    private val httpBase: suspend () -> String,
) {
    suspend fun execute(body: BodyParams): CommandResult {
        val name = FilePaths.name(body.requiredString("name"))
        val token = body.requiredString("media").removePrefix("MEDIA:hermes-relay://")
        if (token.isBlank() || token in listOf(".", "..") || token.any { it == '/' || it == '\\' || it.isISOControl() }) {
            throw BadRequestException("media")
        }
        val sha256 = body.requiredString("sha256")
        if (!FilePull.SHA256.matches(sha256)) throw BadRequestException("sha256")
        val size = body.requiredLong("size")
        if (size < 0) throw BadRequestException("size")
        if (size > FilePaths.MAX_BYTES) operations.file("size", FileResult.Failure(FileFailureKind.TOO_LARGE,
            PortFailure(FailureKind.REJECTED, IllegalArgumentException("The file exceeds 256 MiB."))))
        val overwrite = body.boolean("overwrite", false)
        val directory = operations.file("resolve", files.resolve(body.string("dir") ?: "Download"))
        val target = operations.file("resolve", files.resolve("$directory/$name"))
        when (val existing = files.stat(target)) {
            is FileResult.Success -> {
                if (!overwrite) operations.file("target", FileResult.Failure(FileFailureKind.EXISTS,
                    PortFailure(FailureKind.REJECTED, IllegalStateException("The file already exists."))))
                if (existing.value.kind != FileKind.FILE) throw BadRequestException("name")
            }
            is FileResult.Failure -> if (existing.kind != FileFailureKind.NOT_FOUND) operations.file("stat", existing)
        }
        val endpoint = operations.endpoint(httpBase, ErrorCode.MEDIA_FETCH_FAILED)
        val temporary = "$target.part"
        val handle = operations.file("create", files.create(temporary, size))
        var renamed = false
        try {
            val info = operations.transfer("fetch", ErrorCode.MEDIA_FETCH_FAILED) {
                media.fetchToFile(endpoint, token, handle, FilePaths.MAX_BYTES)
            }
            if (info.size != size || !info.sha256.equals(sha256, ignoreCase = true)) {
                operations.portFailure("verify", PortFailure(FailureKind.INVALID_DATA,
                    IllegalStateException("The downloaded size or checksum does not match.")), ErrorCode.CHECKSUM_MISMATCH)
            }
            operations.file("rename", files.rename(temporary, target, overwrite))
            renamed = true
            val scanned = scan(target)
            return CommandResult.Success(buildJsonObject {
                put("path", target); put("size", info.size); put("sha256", info.sha256.lowercase()); put("scanned", scanned)
            })
        } finally {
            if (!renamed) withContext(NonCancellable + ioDispatcher) { handle.discard() }
        }
    }

    private suspend fun scan(path: String): Boolean {
        val result = try { withTimeout(10_000) { files.scan(path, 10_000) } }
        catch (cause: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            FileResult.Failure(FileFailureKind.IO, PortFailure(FailureKind.TIMED_OUT, cause))
        }
        val scanned = when (result) {
            is FileResult.Success -> result.value
            is FileResult.Failure -> {
                logger.warn("FilePush", "scan", "state=unscanned", FailureDiagnostics.sanitized(result.failure.cause))
                return false
            }
        }
        if (!scanned) logger.warn("FilePush", "scan", "state=unscanned")
        return scanned
    }
}
