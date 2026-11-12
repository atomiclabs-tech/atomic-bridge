package tech.atomiclabs.link.device.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import java.io.IOException
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.*
import tech.atomiclabs.link.R
import tech.atomiclabs.link.core.audio.ProbeTone
import tech.atomiclabs.link.core.command.FailureDiagnostics
import tech.atomiclabs.link.core.log.Logger
import tech.atomiclabs.link.core.port.*

class AndroidTonePlayer(private val context: Context, private val clock: Clock, private val logger: Logger,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val createTrack: (AudioPlaybackStream) -> AudioTrack = ::track,
    private val speech: () -> ByteArray = { context.resources.openRawResource(R.raw.probe_clip).use { it.readBytes() } }) : AudioPlayback {
    override suspend fun play(request: AudioPlaybackRequest): PortResult<AudioPlaybackMeasurement> {
        var track: AudioTrack? = null
        var started = false
        var problem: Throwable? = null
        try {
            val finished = withTimeoutOrNull(15_000) { withContext(dispatcher) {
                val tone = ProbeTone.pcm(request.toneFrequencyHz, request.toneDurationMs)
                val clip = if (request.includeSpeech) speech().also {
                    check(it.isNotEmpty() && it.size % 2 == 0 && it.size <= 160_000) { "The bundled speech clip has an invalid PCM16 length." }
                } else byteArrayOf()
                val pcm = tone + clip
                val player = createTrack(request.stream).also { track = it }
                check(player.state == AudioTrack.STATE_INITIALIZED) { "AudioTrack did not initialize." }
                player.play(); started = true
                check(player.playState == AudioTrack.PLAYSTATE_PLAYING) { "AudioTrack did not start playback." }
                var written = 0
                while (written < pcm.size) {
                    currentCoroutineContext().ensureActive()
                    val count = player.write(pcm, written, pcm.size - written, AudioTrack.WRITE_NON_BLOCKING)
                    check(count >= 0) { "AudioTrack write failed with code $count." }
                    check(count <= pcm.size - written && count % 2 == 0) { "AudioTrack returned an invalid PCM16 byte count." }
                    written += count
                    if (written < pcm.size) clock.sleep(2)
                }
                while (Integer.toUnsignedLong(player.playbackHeadPosition) < pcm.size / 2) clock.sleep(5)
                true
            } }
            if (finished != true) throw TimeoutException("AudioTrack exceeded its playback budget.")
        } catch (cause: CancellationException) { problem = cause
        } catch (cause: SecurityException) { problem = cause
        } catch (cause: IllegalArgumentException) { problem = cause
        } catch (cause: IllegalStateException) { problem = cause
        } catch (cause: UnsupportedOperationException) { problem = cause
        } catch (cause: android.content.res.Resources.NotFoundException) { problem = cause
        } catch (cause: IOException) { problem = cause
        } catch (cause: TimeoutException) { problem = cause
        } finally {
            withContext(NonCancellable) {
                val cleanupFinished = withTimeoutOrNull(250) { withContext(dispatcher) {
                    val cleanup = AudioCleanup(logger, "AndroidTonePlayer")
                    track?.let { player ->
                        if (started) problem = cleanup.finish(problem, "stop") { player.stop() }
                        problem = cleanup.finish(problem, "release") { player.release() }
                    }
                    true
                } }
                if (cleanupFinished != true) {
                    val timeout = TimeoutException("Audio cleanup exceeded its shutdown budget.")
                    logger.error("AndroidTonePlayer", "cleanup", "state=timed_out", FailureDiagnostics.sanitized(timeout))
                    val original = problem
                    if (original == null) problem = timeout else original.addSuppressed(timeout)
                }
            }
        }
        val cause = problem
        if (cause is CancellationException) throw cause
        if (cause != null) logger.error("AndroidTonePlayer", "play", "stream=${request.stream.name} speaker=${request.speaker}",
            FailureDiagnostics.sanitized(cause))
        return PortResult.Success(AudioPlaybackMeasurement(request.stream, request.speaker, cause == null,
            cause?.let { it.message ?: it.javaClass.simpleName }))
    }

    companion object {
        private fun track(stream: AudioPlaybackStream): AudioTrack {
            val size = AudioTrack.getMinBufferSize(16_000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            check(size > 0) { "AudioTrack minimum buffer failed with code $size." }
            val usage = when (stream) {
                AudioPlaybackStream.USAGE_VOICE_COMMUNICATION -> AudioAttributes.USAGE_VOICE_COMMUNICATION
                AudioPlaybackStream.USAGE_MEDIA -> AudioAttributes.USAGE_MEDIA
            }
            return AudioTrack(AudioAttributes.Builder().setUsage(usage).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(),
                AudioFormat.Builder().setSampleRate(16_000).setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build(), size, AudioTrack.MODE_STREAM, AudioManager.AUDIO_SESSION_ID_GENERATE)
        }
    }
}
