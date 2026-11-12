package tech.atomiclabs.link.net

import java.io.IOException
import java.security.MessageDigest
import okhttp3.MediaType
import okhttp3.RequestBody
import okhttp3.ResponseBody
import okhttp3.MediaType.Companion.toMediaType
import okio.BufferedSink
import tech.atomiclabs.link.core.port.*

/** One-shot body: cancellation closes the source and prevents a late callback from reading it again. */
internal class FileUploadBody(private val source: FileReadHandle, private val type: String) : RequestBody(), AutoCloseable {
    private val lock = Any()
    private var closed = false
    private var result: FileTransferInfo? = null
    override fun contentType(): MediaType = type.toMediaType()
    override fun contentLength() = source.size
    override fun isOneShot() = true

    override fun writeTo(sink: BufferedSink) {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(8_192)
        var size = 0L
        while (true) {
            val count = synchronized(lock) {
                if (closed) throw IOException("The upload source is closed.")
                source.read(buffer)
            }
            if (count == -1) break
            require(count in 1..buffer.size) { "The file source returned an invalid chunk." }
            size += count
            require(size <= source.size && size <= RelayMediaClient.MAX_STREAM_BYTES) { "The file exceeds its size limit." }
            digest.update(buffer, 0, count)
            sink.write(buffer, 0, count)
        }
        require(size == source.size) { "The file size changed during upload." }
        result = FileTransferInfo(size, digest.hex(), contentType().toString())
    }

    fun info(): FileTransferInfo = checkNotNull(result) { "The file body was not uploaded." }

    override fun close() = synchronized(lock) {
        if (!closed) { closed = true; source.close() }
    }
}

/** Serializes target writes and abort, so timeout/cancellation cannot recreate a discarded partial file. */
internal class FileDownload(private val target: FileWriteHandle, private val maxBytes: Long) {
    private val lock = Any()
    private var closed = false
    private var discarded = false

    fun copy(body: ResponseBody): FileTransferInfo {
        require(body.contentLength() <= maxBytes) { "The media file exceeds its size limit." }
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(8_192)
        var size = 0L
        val input = body.byteStream()
        while (true) {
            val count = input.read(buffer)
            if (count == -1) break
            size += count
            require(size <= maxBytes) { "The media file exceeds its size limit." }
            synchronized(lock) {
                if (closed) throw IOException("The download target is closed.")
                target.write(buffer, count)
            }
            digest.update(buffer, 0, count)
        }
        require(size == target.expectedSize) { "The downloaded file size does not match." }
        synchronized(lock) {
            if (closed) throw IOException("The download target is closed.")
            closed = true
            target.close()
        }
        return FileTransferInfo(size, digest.hex(), body.contentType()?.toString())
    }

    fun abort() = synchronized(lock) {
        if (!discarded) {
            closed = true; discarded = true
            target.discard()
        }
    }
}

private fun MessageDigest.hex(): String = digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
