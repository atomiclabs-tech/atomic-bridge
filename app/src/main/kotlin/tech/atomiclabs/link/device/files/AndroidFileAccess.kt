package tech.atomiclabs.link.device.files

import android.content.Context
import android.media.MediaScannerConnection
import android.os.Environment
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import kotlin.coroutines.resume
import kotlinx.coroutines.*
import tech.atomiclabs.link.core.command.FailureDiagnostics
import tech.atomiclabs.link.core.files.FilePathFailure
import tech.atomiclabs.link.core.files.FilePaths
import tech.atomiclabs.link.core.files.FileRoutes
import tech.atomiclabs.link.core.log.Logger
import tech.atomiclabs.link.core.port.*

/** Filesystem and scanner adapter; each operation rechecks access and canonical confinement. */
class AndroidFileAccess(
    private val context: Context,
    private val logger: Logger,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : FileAccess {
    private val moves = Any()

    override suspend fun roots(): FileResult<List<FileEntry>> = perform("roots") {
        val policy = policy()
        policy.listedRoots().map { path ->
            val file = checked(path, policy)
            // These are logical navigation roots, including folders not created by the owner yet.
            val modified = try { attributes(file).lastModifiedTime().toMillis() }
                catch (cause: NoSuchFileException) { 0L }
            FileEntry(file.name, file.path, FileKind.DIRECTORY, 0, modified)
        }
    }

    override suspend fun resolve(path: String): FileResult<String> = perform("resolve") { checked(path).path }

    override suspend fun list(path: String): FileResult<List<FileEntry>> = perform("list") {
        val directory = checked(path)
        val metadata = attributes(directory)
        if (!metadata.isDirectory) throw IOException("The requested path is not a directory.")
        val children = directory.listFiles() ?: throw IOException("The directory could not be listed.")
        children.map { child -> entry(checked(child.path), enforceSize = false) }
    }

    override suspend fun stat(path: String): FileResult<FileEntry> = perform("stat") { entry(checked(path)) }

    override suspend fun open(path: String): FileResult<FileReadHandle> = acquire("open", { it.close() }) {
        val file = checked(path)
        val metadata = entry(file)
        if (metadata.kind != FileKind.FILE) throw IOException("The requested path is not a regular file.")
        val stream = Files.newInputStream(file.toPath(), LinkOption.NOFOLLOW_LINKS)
        AndroidFileReadHandle(stream, metadata.size, logger)
    }

    override suspend fun create(path: String, expectedSize: Long): FileResult<FileWriteHandle> = acquire("create", { it.discard() }) {
        if (expectedSize > FilePaths.MAX_BYTES) throw FilePathFailure(FileFailureKind.TOO_LARGE)
        require(expectedSize >= 0) { "The expected file size is invalid." }
        val file = writable(path)
        val parent = file.parentFile ?: throw FilePathFailure(FileFailureKind.FORBIDDEN)
        if (Files.exists(parent.toPath()) && !Files.isDirectory(parent.toPath())) {
            throw IOException("The target parent is not a directory.")
        }
        Files.createDirectories(parent.toPath())
        val verified = writable(path)
        if (verified != file) throw FilePathFailure(FileFailureKind.FORBIDDEN)
        val stream = Files.newOutputStream(file.toPath(), StandardOpenOption.WRITE, StandardOpenOption.CREATE_NEW,
            LinkOption.NOFOLLOW_LINKS)
        AndroidFileWriteHandle(stream, file, expectedSize, logger) {
            if (writable(path) != file) throw FilePathFailure(FileFailureKind.FORBIDDEN)
        }
    }

    override suspend fun rename(source: String, target: String, overwrite: Boolean): FileResult<Unit> = perform("rename") {
        synchronized(moves) {
            val from = writable(source)
            val to = writable(target)
            if (!overwrite && Files.exists(to.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                throw FilePathFailure(FileFailureKind.EXISTS)
            }
            if (entry(from).kind != FileKind.FILE) throw IOException("The temporary path is not a regular file.")
            if (overwrite) Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING)
            else Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE)
        }
    }

    override suspend fun deleteTemp(path: String): FileResult<Unit> = perform("deleteTemp") {
        val file = writable(path)
        if (!file.name.endsWith(".part")) throw FilePathFailure(FileFailureKind.FORBIDDEN)
        Files.deleteIfExists(file.toPath())
    }

    override suspend fun scan(path: String, timeoutMs: Long): FileResult<Boolean> = perform("scan", timeoutMs) {
        val file = checked(path)
        entry(file)
        suspendCancellableCoroutine { continuation ->
            MediaScannerConnection.scanFile(context, arrayOf(file.path), null) { _, uri ->
                if (continuation.isActive) continuation.resume(uri != null)
            }
        }
    }

    private fun policy(): FilePaths {
        if (!Environment.isExternalStorageManager()) throw SecurityException("All files access is missing.")
        if (Environment.getExternalStorageState() !in listOf(Environment.MEDIA_MOUNTED, Environment.MEDIA_MOUNTED_READ_ONLY)) {
            throw FilePathFailure(FileFailureKind.STORAGE_UNAVAILABLE)
        }
        val shared = Environment.getExternalStorageDirectory().canonicalPath
        val cache = context.cacheDir.canonicalFile
        return FilePaths(shared, File(cache, "outbound").path, File(cache, "audio-probe").path)
    }

    private fun checked(path: String, policy: FilePaths = policy()): File =
        File(policy.resolve(path) { File(it).canonicalPath })

    private fun writable(path: String): File = File(policy().resolveWrite(path) { File(it).canonicalPath })

    private fun attributes(file: File): BasicFileAttributes =
        Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)

    private fun entry(file: File, enforceSize: Boolean = true): FileEntry {
        val metadata = attributes(file)
        if (!metadata.isRegularFile && !metadata.isDirectory) throw FilePathFailure(FileFailureKind.FORBIDDEN)
        if (enforceSize && metadata.isRegularFile && metadata.size() > FilePaths.MAX_BYTES) {
            throw FilePathFailure(FileFailureKind.TOO_LARGE)
        }
        return FileEntry(file.name, file.path, if (metadata.isDirectory) FileKind.DIRECTORY else FileKind.FILE,
            if (metadata.isDirectory) 0 else metadata.size(), metadata.lastModifiedTime().toMillis())
    }

    private suspend fun <T> acquire(operation: String, cleanup: (T) -> Unit, block: () -> T): FileResult<T> {
        var resource: T? = null
        var delivered = false
        return try {
            perform(operation) { block().also { resource = it } }.also { delivered = it is FileResult.Success }
        } finally {
            if (!delivered) resource?.let { withContext(NonCancellable + dispatcher) { cleanup(it) } }
        }
    }

    private suspend fun <T> perform(operation: String, timeoutMs: Long = 3_000, block: suspend () -> T): FileResult<T> = try {
        FileResult.Success(withTimeout(timeoutMs) { withContext(dispatcher) { block() } })
    } catch (cause: TimeoutCancellationException) {
        currentCoroutineContext().ensureActive()
        failure(operation, cause, FileFailureKind.IO, FailureKind.TIMED_OUT)
    } catch (cause: FilePathFailure) {
        failure(operation, cause, cause.kind)
    } catch (cause: NoSuchFileException) {
        failure(operation, cause, FileFailureKind.NOT_FOUND)
    } catch (cause: FileNotFoundException) {
        failure(operation, cause, FileFailureKind.NOT_FOUND)
    } catch (cause: FileAlreadyExistsException) {
        failure(operation, cause, FileFailureKind.EXISTS)
    } catch (cause: SecurityException) {
        failure(operation, cause, FileFailureKind.PERMISSION_DENIED, FailureKind.PERMISSION_DENIED)
    } catch (cause: IOException) {
        failure(operation, cause, FileFailureKind.IO)
    } catch (cause: IllegalArgumentException) {
        failure(operation, cause, FileFailureKind.IO, FailureKind.INVALID_DATA)
    } catch (cause: IllegalStateException) {
        failure(operation, cause, FileFailureKind.IO, FailureKind.PLATFORM)
    }

    private fun failure(operation: String, cause: Throwable, kind: FileFailureKind,
        portKind: FailureKind = FailureKind.IO): FileResult.Failure {
        logger.error("AndroidFileAccess", operation, "state=failed", FailureDiagnostics.sanitized(cause))
        return FileResult.Failure(kind, PortFailure(portKind, cause,
            permission = if (kind == FileFailureKind.PERMISSION_DENIED) FileRoutes.ALL_FILES else null))
    }
}
