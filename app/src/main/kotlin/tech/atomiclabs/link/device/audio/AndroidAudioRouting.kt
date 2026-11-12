package tech.atomiclabs.link.device.audio

import android.media.AudioDeviceInfo
import android.media.AudioManager
import kotlinx.coroutines.*
import tech.atomiclabs.link.core.audio.SpeakerControl
import tech.atomiclabs.link.core.command.FailureDiagnostics
import tech.atomiclabs.link.core.log.Logger
import tech.atomiclabs.link.core.port.*

class AndroidAudioRouting(private val manager: AudioManager, tree: UiTreeSource,
    private val state: CallStateSource, private val clock: Clock, private val logger: Logger,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO) : AudioRouting {
    private val control = SpeakerControl(tree)
    override suspend fun callState() = state.read()

    override suspend fun setSpeaker(enabled: Boolean): PortResult<List<SpeakerAttempt>> {
        val attempts = mutableListOf<SpeakerAttempt>()
        attempts += method(enabled, "communication_device") {
            val type = if (enabled) AudioDeviceInfo.TYPE_BUILTIN_SPEAKER else AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
            val target = manager.availableCommunicationDevices.firstOrNull { it.type == type }
                ?: error("The requested communication device is unavailable.")
            check(manager.setCommunicationDevice(target)) { "The platform rejected the communication device." }
            awaitRoute { manager.communicationDevice?.id == target.id }
        }
        if (attempts.last().switched) return PortResult.Success(attempts)
        logger.warn("AndroidAudioRouting", "speaker_fallback", "via=speakerphone speaker=$enabled")
        attempts += method(enabled, "speakerphone") {
            manager.isSpeakerphoneOn = enabled
            awaitRoute { manager.isSpeakerphoneOn == enabled }
        }
        if (attempts.last().switched) return PortResult.Success(attempts)
        logger.warn("AndroidAudioRouting", "speaker_fallback", "via=accessibility_speaker speaker=$enabled")
        attempts += method(enabled, "accessibility_speaker") {
            if (manager.isSpeakerphoneOn != enabled) {
                when (val clicked = control.click()) {
                    is PortResult.Failure -> throw IllegalStateException("Speaker control failed: ${clicked.failure.cause.message}", clicked.failure.cause)
                    is PortResult.Success -> Unit
                }
            }
            awaitRoute { manager.isSpeakerphoneOn == enabled }
        }
        return PortResult.Success(attempts)
    }

    private suspend fun awaitRoute(matches: () -> Boolean) {
        while (!matches()) clock.sleep(25)
    }

    private suspend fun method(speaker: Boolean, via: String, block: suspend () -> Unit): SpeakerAttempt = try {
        withTimeout(2_000) { withContext(dispatcher) { block() } }
        SpeakerAttempt(speaker, via, true)
    } catch (cause: TimeoutCancellationException) { failure(speaker, via, cause)
    } catch (cause: CancellationException) { throw cause
    } catch (cause: SecurityException) { failure(speaker, via, cause)
    } catch (cause: IllegalArgumentException) { failure(speaker, via, cause)
    } catch (cause: IllegalStateException) { failure(speaker, via, cause)
    } catch (cause: UnsupportedOperationException) { failure(speaker, via, cause)
    }

    private fun failure(speaker: Boolean, via: String, cause: Exception): SpeakerAttempt {
        logger.error("AndroidAudioRouting", "set_speaker", "via=$via speaker=$speaker", FailureDiagnostics.sanitized(cause))
        return SpeakerAttempt(speaker, via, false, cause.message ?: cause.javaClass.simpleName)
    }
}
