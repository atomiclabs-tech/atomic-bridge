package tech.atomiclabs.link.device.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.IOException
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.*
import tech.atomiclabs.link.core.audio.Loudness
import tech.atomiclabs.link.core.audio.WavWriter
import tech.atomiclabs.link.core.command.FailureDiagnostics
import tech.atomiclabs.link.core.log.Logger
import tech.atomiclabs.link.core.port.*

class AndroidAudioProbe(private val clock: Clock, private val logger: Logger,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val minimumBuffer: () -> Int = { AudioRecord.getMinBufferSize(16_000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT) },
    private val createRecord: (Int, Int) -> AudioRecord = { source, size ->
        AudioRecord(source, 16_000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, size)
    }) : AudioCapture {
    override suspend fun capture(request: AudioCaptureRequest): PortResult<AudioCaptureMeasurement> {
        var record: AudioRecord? = null
        var started = false
        var problem: Throwable? = null
        var measured: AudioCaptureMeasurement? = null
        try {
            require(request.durationMs in 1_000..10_000) { "Capture duration is outside the probe bounds." }
            val finished = withTimeoutOrNull(request.durationMs + 2_000) { withContext(dispatcher) {
                val size = minimumBuffer()
                check(size > 0) { "AudioRecord minimum buffer failed with code $size." }
                val recorder = createRecord(source(request.source), maxOf(size, 1_600)).also { record = it }
                check(recorder.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord did not initialize." }
                recorder.startRecording(); started = true
                check(recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "AudioRecord did not start recording." }
                val pcm = ByteArray((request.durationMs * 32).toInt())
                val deadline = clock.monotonicMs() + request.durationMs
                var count = 0
                while (clock.monotonicMs() < deadline && count < pcm.size) {
                    currentCoroutineContext().ensureActive()
                    val read = recorder.read(pcm, count, minOf(3_200, pcm.size - count), AudioRecord.READ_NON_BLOCKING)
                    if (read < 0) throw ReadFailure(read)
                    check(read <= minOf(3_200, pcm.size - count) && read % 2 == 0) { "AudioRecord returned an invalid PCM16 byte count." }
                    count += read
                    if (count < pcm.size) clock.sleep(2)
                }
                val levels = Loudness.measure(pcm, count)
                val header = WavWriter.header(count)
                request.sample.target.write(header, header.size)
                request.sample.target.write(pcm, count)
                request.sample.target.close()
                measured = AudioCaptureMeasurement(request.source, request.speaker, AudioInitialization.OK,
                    count.toLong(), levels.rms, levels.peak, levels.silent, request.sample.path)
                true
            } }
            if (finished != true) throw TimeoutException("AudioRecord exceeded its capture budget.")
        } catch (cause: CancellationException) { problem = cause
        } catch (cause: SecurityException) { problem = cause
        } catch (cause: IllegalArgumentException) { problem = cause
        } catch (cause: IllegalStateException) { problem = cause
        } catch (cause: UnsupportedOperationException) { problem = cause
        } catch (cause: IOException) { problem = cause
        } catch (cause: TimeoutException) { problem = cause
        } finally {
            withContext(NonCancellable) {
                val cleanupFinished = withTimeoutOrNull(250) { withContext(dispatcher) {
                    val cleanup = AudioCleanup(logger, "AndroidAudioProbe")
                    record?.let { recorder ->
                        if (started) problem = cleanup.finish(problem, "stop") { recorder.stop() }
                        problem = cleanup.finish(problem, "release") { recorder.release() }
                    }
                    if (problem != null) problem = cleanup.finish(problem, "sample_discard") { request.sample.target.discard() }
                    true
                } }
                if (cleanupFinished != true) {
                    val timeout = TimeoutException("Audio cleanup exceeded its shutdown budget.")
                    logger.error("AndroidAudioProbe", "cleanup", "state=timed_out", FailureDiagnostics.sanitized(timeout))
                    val original = problem
                    if (original == null) problem = timeout else original.addSuppressed(timeout)
                }
            }
        }
        val cause = problem
        if (cause is CancellationException) throw cause
        if (cause != null) {
            logger.error("AndroidAudioProbe", "capture", "source=${request.source.name} speaker=${request.speaker}", FailureDiagnostics.sanitized(cause))
            return PortResult.Failure(PortFailure(when (cause) {
                is SecurityException -> FailureKind.PERMISSION_DENIED
                is TimeoutException -> FailureKind.TIMED_OUT
                else -> FailureKind.PLATFORM
            }, cause,
                platformCode = (cause as? ReadFailure)?.code))
        }
        return PortResult.Success(checkNotNull(measured))
    }

    private fun source(source: AudioCaptureSource): Int = when (source) {
        AudioCaptureSource.VOICE_CALL -> MediaRecorder.AudioSource.VOICE_CALL
        AudioCaptureSource.VOICE_DOWNLINK -> MediaRecorder.AudioSource.VOICE_DOWNLINK
        AudioCaptureSource.VOICE_UPLINK -> MediaRecorder.AudioSource.VOICE_UPLINK
        AudioCaptureSource.VOICE_COMMUNICATION -> MediaRecorder.AudioSource.VOICE_COMMUNICATION
        AudioCaptureSource.VOICE_RECOGNITION -> MediaRecorder.AudioSource.VOICE_RECOGNITION
        AudioCaptureSource.MIC -> MediaRecorder.AudioSource.MIC
        AudioCaptureSource.UNPROCESSED -> MediaRecorder.AudioSource.UNPROCESSED
    }

    private class ReadFailure(val code: Int) : IllegalStateException("AudioRecord read failed with code $code.")
}
