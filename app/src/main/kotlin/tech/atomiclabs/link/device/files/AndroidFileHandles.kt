package tech.atomiclabs.link.device.files

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import tech.atomiclabs.link.core.command.FailureDiagnostics
import tech.atomiclabs.link.core.files.FilePathFailure
import tech.atomiclabs.link.core.log.Logger
import tech.atomiclabs.link.core.port.FileReadHandle
import tech.atomiclabs.link.core.port.FileWriteHandle

internal class AndroidFileReadHandle(
    private val stream: InputStream,
    override val size: Long,
    private val logger: Logger,
) : FileReadHandle {
    private var closed = false
    @Synchronized override fun read(buffer: ByteArray): Int = io("read", logger) {
        if (closed) throw IOException("The file source is closed.")
        stream.read(buffer)
    }
    @Synchronized override fun close() = io("closeRead", logger) {
        if (!closed) { closed = true; stream.close() }
    }
}

internal class AndroidFileWriteHandle(
    private val stream: OutputStream,
    private val file: File,
    override val expectedSize: Long,
    private val logger: Logger,
    private val verifyDelete: () -> Unit,
) : FileWriteHandle {
    private var closed = false
    private var discarded = false
    private var size = 0L
    @Synchronized override fun write(buffer: ByteArray, count: Int) = io("write", logger) {
        if (closed) throw IOException("The temporary file is closed.")
        if (count !in 1..buffer.size || size + count > expectedSize) throw IOException("The file exceeds its expected size.")
        stream.write(buffer, 0, count)
        size += count
    }
    @Synchronized override fun close() = io("closeWrite", logger) {
        if (!closed) { closed = true; stream.close() }
    }
    @Synchronized override fun discard() = io("discard", logger) {
        if (!discarded) {
            try { close() } finally { verifyDelete(); Files.deleteIfExists(file.toPath()) }
            discarded = true
        }
    }
}

private inline fun <T> io(operation: String, logger: Logger, block: () -> T): T = try { block() }
catch (cause: IOException) {
    logger.error("AndroidFileHandles", operation, "state=failed", FailureDiagnostics.sanitized(cause))
    throw cause
} catch (cause: FilePathFailure) {
    logger.error("AndroidFileHandles", operation, "state=failed", FailureDiagnostics.sanitized(cause))
    throw cause
} catch (cause: SecurityException) {
    logger.error("AndroidFileHandles", operation, "state=failed", FailureDiagnostics.sanitized(cause))
    throw cause
}
