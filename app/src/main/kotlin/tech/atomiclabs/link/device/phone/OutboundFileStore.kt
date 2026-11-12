// Ported from upstream app/src/main/kotlin/com/hermesandroid/relay/util/MediaCacheWriter.kt at commit da782a8.
package tech.atomiclabs.link.device.phone

import android.content.Context
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException
import kotlinx.coroutines.*
import tech.atomiclabs.link.core.command.FailureDiagnostics
import tech.atomiclabs.link.core.log.Logger
import tech.atomiclabs.link.core.port.*

class OutboundFileStore(
    private val context: Context,
    private val logger: Logger,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : OutboundFiles {
    override suspend fun prune(olderThanEpochMs: Long): PortResult<Unit> = perform("prune") {
        val root = directory()
        val entries = root.listFiles() ?: throw IOException("The outbound directory could not be listed.")
        entries.forEach { file ->
            checkParent(file, root)
            if (file.isFile && file.lastModified() < olderThanEpochMs && !file.delete()) {
                throw IOException("An expired outbound file could not be deleted.")
            }
        }
    }

    override suspend fun write(bytes: ByteArray, fileName: String?, contentType: String): PortResult<OutboundAttachment> =
        perform("write") {
            require(contentType.isNotBlank()) { "The attachment content type is missing." }
            val root = directory()
            val suffix = fileName?.let { "-" + sanitizedName(it) }
            // Each write owns a fresh unexposed path; another compose request's file is never overwritten.
            val file = File.createTempFile("attachment-", suffix, root)
            try {
                checkParent(file, root)
                file.outputStream().use { it.write(bytes) }
                if (file.length() != bytes.size.toLong()) throw IOException("The attachment write was incomplete.")
                val authority = context.packageName + ".files"
                val uri = FileProvider.getUriForFile(context, authority, file)
                require(uri.scheme == "content" && uri.authority == authority) { "The outbound URI is invalid." }
                OutboundAttachment(uri.toString(), contentType, file.name)
            } catch (cause: Exception) {
                cleanup(file, cause)
                throw cause
            }
        }

    private fun directory(): File {
        val cache = context.cacheDir.canonicalFile
        val root = File(cache, "outbound")
        if ((!root.isDirectory && !root.mkdirs()) || !root.isDirectory) {
            throw IOException("The outbound directory could not be created.")
        }
        if (root.canonicalFile != root.absoluteFile) throw IOException("The outbound directory is a symbolic link.")
        checkParent(root, cache)
        return root.canonicalFile
    }

    private fun checkParent(file: File, root: File) {
        if (file.canonicalFile.parentFile != root) throw IOException("The outbound path escapes its directory.")
    }

    private fun sanitizedName(name: String): String {
        val safe = name.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[^A-Za-z0-9._-]"), "_").trim('.').take(80)
        require(safe.isNotBlank()) { "The attachment file name is invalid." }
        return safe
    }

    private fun cleanup(file: File, original: Exception) {
        try {
            if (file.exists() && !file.delete()) throw IOException("The incomplete outbound file could not be removed.")
        } catch (cause: Exception) {
            original.addSuppressed(cause)
            logger.error("OutboundFileStore", "cleanup", "state=failed", FailureDiagnostics.sanitized(cause))
        }
    }

    private suspend fun <T> perform(operation: String, block: () -> T): PortResult<T> = try {
        PortResult.Success(withTimeout(3_000) { withContext(dispatcher) { block() } })
    } catch (cause: TimeoutCancellationException) {
        failure(operation, cause, FailureKind.TIMED_OUT)
    } catch (cause: CancellationException) {
        throw cause
    } catch (cause: Exception) {
        failure(operation, cause, when (cause) {
            is SecurityException -> FailureKind.PERMISSION_DENIED
            is IllegalArgumentException -> FailureKind.INVALID_DATA
            else -> FailureKind.IO
        })
    }

    private fun failure(operation: String, cause: Exception, kind: FailureKind): PortResult.Failure {
        logger.error("OutboundFileStore", operation, "state=failed", FailureDiagnostics.sanitized(cause))
        return PortResult.Failure(PortFailure(kind, cause))
    }
}
