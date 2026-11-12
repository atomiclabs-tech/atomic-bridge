package tech.atomiclabs.link.core.fakes

import java.io.ByteArrayOutputStream
import java.io.IOException
import tech.atomiclabs.link.core.port.*

class FakeFileReadHandle(private val bytes: ByteArray, override val size: Long = bytes.size.toLong(),
    private val chunkSize: Int = 8_192) : FileReadHandle {
    var readFailure: IOException? = null
    var closed = false
    var reads = 0
    private var offset = 0
    override fun read(buffer: ByteArray): Int {
        check(!closed)
        reads++
        readFailure?.let { throw it }
        if (offset == bytes.size) return -1
        val count = minOf(buffer.size, chunkSize, bytes.size - offset)
        bytes.copyInto(buffer, 0, offset, offset + count)
        offset += count
        return count
    }
    override fun close() { closed = true }
}

class FakeFileWriteHandle(override val expectedSize: Long) : FileWriteHandle {
    private val output = ByteArrayOutputStream()
    val bytes: ByteArray get() = output.toByteArray()
    var writeFailure: IOException? = null
    var discardFailure: IOException? = null
    var closed = false
    var discarded = false
    var writes = 0
    override fun write(buffer: ByteArray, count: Int) {
        check(!closed)
        writes++
        writeFailure?.let { throw it }
        output.write(buffer, 0, count)
    }
    override fun close() { closed = true }
    override fun discard() {
        discardFailure?.let { throw it }
        closed = true; discarded = true; output.reset()
    }
}

class FakeFileAccess : FileAccess {
    val calls = mutableListOf<String>()
    var failure: FileResult.Failure? = null
    var entries = emptyList<FileEntry>()
    var entry = FileEntry("fixture", "fixture", FileKind.FILE, 0, 0)
    var reader: FileReadHandle = FakeFileReadHandle(byteArrayOf())
    var writer: FileWriteHandle = FakeFileWriteHandle(0)
    var scanned = true
    private fun <T> result(operation: String, value: T): FileResult<T> {
        calls += operation
        return failure ?: FileResult.Success(value)
    }
    override suspend fun roots() = result("roots", entries)
    override suspend fun resolve(path: String) = result("resolve", path)
    override suspend fun list(path: String) = result("list", entries)
    override suspend fun stat(path: String) = result("stat", entry)
    override suspend fun open(path: String) = result("open", reader)
    override suspend fun create(path: String, expectedSize: Long) = result("create", writer)
    override suspend fun rename(source: String, target: String, overwrite: Boolean) = result("rename", Unit)
    override suspend fun deleteTemp(path: String) = result("deleteTemp", Unit)
    override suspend fun scan(path: String, timeoutMs: Long) = result("scan", scanned)
}
