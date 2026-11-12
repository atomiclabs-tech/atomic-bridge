package tech.atomiclabs.link.core.files

import kotlinx.coroutines.*
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import tech.atomiclabs.link.core.command.*
import tech.atomiclabs.link.core.log.Logger
import tech.atomiclabs.link.core.port.*

class FileRoutes(
    files: FileAccess,
    media: MediaTransfer,
    private val permissions: PermissionState,
    logger: Logger,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    httpBase: suspend () -> String,
) : RouteGroup {
    private val operations = FileOperations(logger)
    private val listing = FileListing(files, operations)
    private val pull = FilePull(files, media, operations, ioDispatcher, httpBase)
    private val push = FilePush(files, media, operations, logger, ioDispatcher, httpBase)
    override val routes = listOf(
        Route("GET", "/files") { execute { listing.execute(it) } },
        Route("POST", "/files/pull") { execute { pull.execute(it) } },
        Route("POST", "/files/push") { execute { push.execute(it) } },
    )

    private suspend fun execute(block: suspend () -> CommandResult): CommandResult = try {
        val permission = try { withTimeout(3_000) { permissions.read() } }
        catch (cause: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            operations.portFailure("permission", PortFailure(FailureKind.TIMED_OUT, cause), ErrorCode.TIMEOUT)
        }
        val snapshot = when (permission) {
            is PortResult.Success -> permission.value
            is PortResult.Failure -> operations.portFailure("permission", permission.failure, ErrorCode.INTERNAL_ERROR)
        }
        if (!snapshot.allFiles) operations.file("permission", FileResult.Failure(FileFailureKind.PERMISSION_DENIED,
            PortFailure(FailureKind.PERMISSION_DENIED, SecurityException("All files access is missing."), ALL_FILES)))
        block()
    } catch (cause: FileOperationFailure) { cause.result }

    companion object { const val ALL_FILES = "android.permission.MANAGE_EXTERNAL_STORAGE" }
}

internal class FileOperations(private val logger: Logger) {
    fun <T> file(operation: String, result: FileResult<T>): T = when (result) {
        is FileResult.Success -> result.value
        is FileResult.Failure -> {
            val code = when (result.kind) {
                FileFailureKind.NOT_FOUND -> ErrorCode.FILE_NOT_FOUND
                FileFailureKind.FORBIDDEN -> ErrorCode.PATH_FORBIDDEN
                FileFailureKind.TOO_LARGE -> ErrorCode.FILE_TOO_LARGE
                FileFailureKind.EXISTS -> ErrorCode.FILE_EXISTS
                FileFailureKind.STORAGE_UNAVAILABLE -> ErrorCode.STORAGE_UNAVAILABLE
                FileFailureKind.PERMISSION_DENIED -> ErrorCode.PERMISSION_DENIED
                FileFailureKind.IO -> if (result.failure.kind == FailureKind.TIMED_OUT) ErrorCode.TIMEOUT else ErrorCode.INTERNAL_ERROR
            }
            portFailure(operation, result.failure, code)
        }
    }

    fun portFailure(operation: String, failure: PortFailure, code: ErrorCode): Nothing {
        logger.error("FileRoutes", operation, "state=failed", FailureDiagnostics.sanitized(failure.cause))
        val detail = failure.httpStatus?.let { "HTTP status $it" } ?: when (code) {
            ErrorCode.UPLOAD_FAILED, ErrorCode.MEDIA_FETCH_FAILED -> failure.kind.name.lowercase()
            else -> code.wireValue
        }
        throw FileOperationFailure(CommandResult.Failure(code, "The file $operation failed with $detail.", buildJsonObject {
            if (code == ErrorCode.PERMISSION_DENIED) put("permission", failure.permission ?: FileRoutes.ALL_FILES)
        }), failure.cause)
    }

    suspend fun endpoint(supplier: suspend () -> String, code: ErrorCode): String {
        val value = try { withTimeout(3_000) { supplier() } }
        catch (cause: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            portFailure("endpoint", PortFailure(FailureKind.TIMED_OUT, cause), code)
        }
        if (value.isBlank()) portFailure("endpoint", PortFailure(FailureKind.INVALID_DATA,
            IllegalStateException("The media address is missing.")), code)
        return value
    }

    suspend fun <T> transfer(operation: String, code: ErrorCode, block: suspend () -> PortResult<T>): T {
        val result = try { withTimeout(270_000) { block() } }
        catch (cause: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            portFailure(operation, PortFailure(FailureKind.TIMED_OUT, cause), code)
        }
        return when (result) {
            is PortResult.Success -> result.value
            is PortResult.Failure -> portFailure(operation, result.failure, code)
        }
    }
}

internal class FileOperationFailure(val result: CommandResult.Failure, cause: Throwable) :
    IllegalStateException("The file operation failed.", cause)
