package tech.atomiclabs.link.core.audio

import java.io.IOException
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import tech.atomiclabs.link.core.command.FailureDiagnostics
import tech.atomiclabs.link.core.log.Logger
import tech.atomiclabs.link.core.port.*

class ProbeRunner(private val capture: AudioCapture, private val playback: AudioPlayback,
    private val routing: AudioRouting, private val samples: AudioSampleStore,
    private val foreground: AudioForeground, private val clock: Clock, private val logger: Logger,
    private val io: CoroutineDispatcher = Dispatchers.IO) : AudioProbe {
    private val mutex = Mutex()

    override suspend fun run(plan: AudioProbePlan): PortResult<AudioProbeReport> {
        require(plan.budgetMs > 1 && plan.routeBudgetMs > 0 && plan.durationMs in 1_000..10_000)
        val started = clock.monotonicMs()
        if (withTimeoutOrNull(plan.budgetMs) { mutex.lock(); true } != true) {
            return failed("queue", TimeoutException("The audio probe budget expired while queued."))
        }
        return try {
            val remaining = plan.budgetMs - (clock.monotonicMs() - started)
            if (remaining <= 1_000) failed("queue", TimeoutException("Too little audio probe budget remains after queueing."))
            else session(plan, remaining)
        } finally { mutex.unlock() }
    }

    private suspend fun session(plan: AudioProbePlan, remaining: Long): PortResult<AudioProbeReport> {
        val state = Progress()
        // Reserve adapter cleanup (250 ms), sample cleanup (250 ms), and foreground stop (500 ms).
        val cleanupMs = minOf(1_000, remaining / 2)
        val sampleCleanupMs = cleanupMs / 4
        val stopCleanupMs = cleanupMs / 2
        var result: PortResult<Unit>? = null
        try {
            result = withTimeoutOrNull(remaining - cleanupMs) { measure(plan, state, sampleCleanupMs) }
        } finally {
            if (state.foregroundAttempted) withContext(NonCancellable) {
                when (val stop = attempt("foreground_stop", stopCleanupMs) { foreground.stopMicrophone() }) {
                    is PortResult.Failure -> state.foreground = state.foreground.copy(
                        error = listOfNotNull(state.foreground.error, "Stop failed: ${message(stop.failure)}").joinToString("; "))
                    is PortResult.Success -> Unit
                }
            }
        }
        if (result is PortResult.Failure) return result
        val call = state.callState ?: return failed("call_state", TimeoutException("Call state was not read within the probe budget."))
        return PortResult.Success(AudioProbeReport(call, state.captures, state.playback, state.speakers,
            state.foreground, truncated = result == null))
    }

    private suspend fun measure(plan: AudioProbePlan, state: Progress, sampleCleanupMs: Long): PortResult<Unit> {
        when (val cleared = attempt("samples_clear", plan.routeBudgetMs) { samples.clear() }) {
            is PortResult.Failure -> return cleared
            is PortResult.Success -> Unit
        }
        when (val call = attempt("call_state", plan.routeBudgetMs) { routing.callState() }) {
            is PortResult.Failure -> return call
            is PortResult.Success -> state.callState = call.value
        }
        state.foregroundAttempted = true
        state.foreground = when (val start = attempt("foreground_start", plan.routeBudgetMs) { foreground.startMicrophone() }) {
            is PortResult.Success -> start.value
            is PortResult.Failure -> MicrophoneForegroundOutcome(false, message(start.failure))
        }
        for (speaker in ProbePlan.speakers(plan)) {
            val switched = speaker(speaker, plan, state)
            for (source in plan.sources) {
                state.captures += if (switched == null) capture(source, speaker, plan, sampleCleanupMs) else captureFailed(source, speaker, switched)
            }
        }
        if (plan.inject) {
            val switched = speaker(true, plan, state)
            for (stream in AudioPlaybackStream.entries) {
                state.playback += if (switched != null) AudioPlaybackMeasurement(stream, true, false, switched)
                else when (val result = attempt("playback_${stream.name}", plan.routeBudgetMs) {
                    playback.play(AudioPlaybackRequest(stream, true))
                }) {
                    is PortResult.Success -> result.value
                    is PortResult.Failure -> AudioPlaybackMeasurement(stream, true, false, message(result.failure))
                }
            }
        }
        return PortResult.Success(Unit)
    }

    private suspend fun speaker(enabled: Boolean, plan: AudioProbePlan, state: Progress): String? {
        return when (val result = attempt("speaker_$enabled", plan.routeBudgetMs) { routing.setSpeaker(enabled) }) {
            is PortResult.Failure -> message(result.failure).also {
                state.speakers += SpeakerAttempt(enabled, "routing", false, it)
            }
            is PortResult.Success -> {
                state.speakers += result.value
                if (result.value.any { it.speaker == enabled && it.switched }) null
                else result.value.mapNotNull { it.error }.joinToString("; ").ifEmpty { "No speaker method switched the requested route." }
            }
        }
    }

    private suspend fun capture(source: AudioCaptureSource, speaker: Boolean, plan: AudioProbePlan,
        cleanupMs: Long): AudioCaptureMeasurement {
        var sample: AudioSampleFile? = null
        var complete = false
        var measurement: AudioCaptureMeasurement? = null
        var cleanupError: String? = null
        try {
            val result = attempt("capture_${source.name}_$speaker", (plan.routeBudgetMs - cleanupMs - 250).coerceAtLeast(1)) {
                when (val created = samples.create(source, speaker)) {
                    is PortResult.Failure -> created
                    is PortResult.Success -> {
                        sample = created.value
                        capture.capture(AudioCaptureRequest(source, speaker, plan.durationMs, created.value))
                    }
                }
            }
            measurement = when (result) {
                is PortResult.Success -> result.value.also { complete = it.init == AudioInitialization.OK }
                is PortResult.Failure -> captureFailed(source, speaker, message(result.failure))
            }
        } finally {
            if (!complete) sample?.let { file -> withContext(NonCancellable) {
                // Reserved shutdown time covers cancellation cleanup as well as foreground stop.
                when (val discarded = attempt("sample_discard", cleanupMs) {
                    withContext(io) { file.target.discard(); PortResult.Success(Unit) }
                }) {
                    is PortResult.Failure -> cleanupError = message(discarded.failure)
                    is PortResult.Success -> Unit
                }
            } }
        }
        val entry = checkNotNull(measurement)
        return if (cleanupError == null) entry else entry.copy(init = AudioInitialization.FAILED,
            sample = null, error = listOfNotNull(entry.error, "Sample cleanup failed: $cleanupError").joinToString("; "))
    }

    private suspend fun <T> attempt(operation: String, budgetMs: Long, block: suspend () -> PortResult<T>): PortResult<T> = try {
        withTimeoutOrNull(budgetMs) { block() }
            ?: failed(operation, TimeoutException("Audio operation $operation exceeded its budget."))
    } catch (cause: CancellationException) { throw cause
    } catch (cause: TimeoutException) { failed(operation, cause)
    } catch (cause: IOException) { failed(operation, cause)
    } catch (cause: SecurityException) { failed(operation, cause)
    } catch (cause: IllegalArgumentException) { failed(operation, cause)
    } catch (cause: IllegalStateException) { failed(operation, cause)
    } catch (cause: UnsupportedOperationException) { failed(operation, cause) }

    private fun failed(operation: String, cause: Exception): PortResult.Failure {
        logger.error("ProbeRunner", operation, "state=failed", FailureDiagnostics.sanitized(cause))
        return PortResult.Failure(PortFailure(if (cause is TimeoutException) FailureKind.TIMED_OUT else FailureKind.PLATFORM, cause))
    }

    private fun captureFailed(source: AudioCaptureSource, speaker: Boolean, error: String) =
        AudioCaptureMeasurement(source, speaker, AudioInitialization.FAILED, 0, 0.0, 0.0, true, error = error)

    private fun message(failure: PortFailure) = failure.cause.message ?: failure.cause.javaClass.simpleName

    private class Progress {
        var callState: CallState? = null
        var foregroundAttempted = false
        var foreground = MicrophoneForegroundOutcome(false)
        val captures = mutableListOf<AudioCaptureMeasurement>()
        val playback = mutableListOf<AudioPlaybackMeasurement>()
        val speakers = mutableListOf<SpeakerAttempt>()
    }
}
