package tech.atomiclabs.link.device.audio

import java.io.File
import java.io.IOException
import java.io.OutputStream
import kotlinx.coroutines.*
import tech.atomiclabs.link.core.command.FailureDiagnostics
import tech.atomiclabs.link.core.log.Logger
import tech.atomiclabs.link.core.port.*

class PrivateAudioSamples(private val cacheDir: File, private val logger: Logger,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO) : AudioSampleStore {
    override suspend fun clear(): PortResult<Unit> = operation("clear") {
        val root = directory()
        val entries = root.listFiles() ?: throw IOException("The private audio sample directory could not be listed.")
        entries.forEach { entry ->
            if (entry.isDirectory || !entry.delete()) throw IOException("A previous private audio sample could not be deleted.")
        }
    }

    override suspend fun create(source: AudioCaptureSource, speaker: Boolean): PortResult<AudioSampleFile> = operation("create") {
        val root = directory()
        val file = File(root, "${source.name.lowercase()}_${if (speaker) "on" else "off"}.wav")
        if (file.canonicalFile.parentFile != root || !file.createNewFile()) throw IOException("A private audio sample could not be created.")
        try {
            AudioSampleFile(file.path, SampleHandle(file, file.outputStream(), logger))
        } catch (cause: IOException) {
            if (!file.delete()) cause.addSuppressed(IOException("The incomplete private audio sample could not be deleted."))
            throw cause
        }
    }

    private fun directory(): File {
        val cache = cacheDir.canonicalFile
        val root = File(cache, "audio-probe")
        if (root.canonicalFile != root) throw IOException("The private audio sample directory escaped the cache.")
        if ((!root.exists() && !root.mkdir()) || !root.isDirectory) throw IOException("The private audio sample directory is unavailable.")
        return root.canonicalFile
    }

    private suspend fun <T> operation(name: String, block: () -> T): PortResult<T> = try {
        withTimeout(2_000) { withContext(dispatcher) { PortResult.Success(block()) } }
    } catch (cause: TimeoutCancellationException) { failure(name, cause, FailureKind.TIMED_OUT)
    } catch (cause: CancellationException) { throw cause
    } catch (cause: IOException) { failure(name, cause, FailureKind.IO)
    } catch (cause: SecurityException) { failure(name, cause, FailureKind.IO)
    } catch (cause: IllegalArgumentException) { failure(name, cause, FailureKind.IO)
    } catch (cause: IllegalStateException) { failure(name, cause, FailureKind.IO)
    } catch (cause: UnsupportedOperationException) { failure(name, cause, FailureKind.IO)
    }

    private fun failure(name: String, cause: Exception, kind: FailureKind): PortResult.Failure {
        logger.error("PrivateAudioSamples", name, "state=failed", FailureDiagnostics.sanitized(cause))
        return PortResult.Failure(PortFailure(kind, cause))
    }

    private class SampleHandle(private val file: File, private val output: OutputStream, logger: Logger) : FileWriteHandle {
        override val expectedSize = -1L
        private var closed = false
        private var discarded = false
        private val cleanup = AudioCleanup(logger, "PrivateAudioSamples")
        @Synchronized override fun write(buffer: ByteArray, count: Int) {
            check(!closed) { "The private sample is closed." }
            require(count in 0..buffer.size)
            output.write(buffer, 0, count)
        }
        @Synchronized override fun close() {
            if (!closed) { output.close(); closed = true }
        }
        @Synchronized override fun discard() {
            if (discarded) return
            var problem = cleanup.finish(null, "sample_close") { close() }
            problem = cleanup.finish(problem, "sample_delete") {
                if (file.exists() && !file.delete()) throw IOException("The private audio sample could not be deleted.")
            }
            problem?.let { throw it }
            discarded = true
        }
    }
}
