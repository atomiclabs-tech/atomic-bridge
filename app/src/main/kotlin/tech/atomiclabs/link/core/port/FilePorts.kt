// forge-allow: no-test declarations only
package tech.atomiclabs.link.core.port

/** Canonical confinement is enforced on every path operation, including symlinks and private cache roots. */
interface FileAccess {
    suspend fun roots(): FileResult<List<FileEntry>>
    suspend fun resolve(path: String): FileResult<String>
    suspend fun list(path: String): FileResult<List<FileEntry>>
    suspend fun stat(path: String): FileResult<FileEntry>
    suspend fun open(path: String): FileResult<FileReadHandle>
    suspend fun create(path: String, expectedSize: Long): FileResult<FileWriteHandle>
    suspend fun rename(source: String, target: String, overwrite: Boolean): FileResult<Unit>
    suspend fun deleteTemp(path: String): FileResult<Unit>
    suspend fun scan(path: String, timeoutMs: Long): FileResult<Boolean>
}

/** Preserves path-policy distinctions without requiring callers to inspect exception messages or platform types. */
sealed interface FileResult<out T> {
    data class Success<T>(val value: T) : FileResult<T>
    data class Failure(val kind: FileFailureKind, val failure: PortFailure) : FileResult<Nothing>
}
enum class FileFailureKind { NOT_FOUND, FORBIDDEN, TOO_LARGE, EXISTS, STORAGE_UNAVAILABLE, PERMISSION_DENIED, IO }

enum class FileKind { FILE, DIRECTORY }
data class FileEntry(val name: String, val path: String, val kind: FileKind, val size: Long, val modified: Long)

/** Blocking chunk I/O, used off the main thread. Throws on I/O failure; -1 alone means end of input. */
interface FileReadHandle : AutoCloseable {
    val size: Long
    fun read(buffer: ByteArray): Int
    override fun close()
}

/** Created temporary file. discard closes and deletes it, even after close; both operations are idempotent. */
interface FileWriteHandle : AutoCloseable {
    /** Known length for media download; private audio capture may use -1 until recording finishes. */
    val expectedSize: Long
    fun write(buffer: ByteArray, count: Int)
    override fun close()
    fun discard()
}

data class FileTransferInfo(val size: Long, val sha256: String, val contentType: String?)
data class UploadedFile(val token: String, val info: FileTransferInfo)
