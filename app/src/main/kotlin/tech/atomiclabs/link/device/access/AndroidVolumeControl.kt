package tech.atomiclabs.link.device.access

import android.media.AudioManager
import kotlinx.coroutines.*
import tech.atomiclabs.link.core.command.FailureDiagnostics
import tech.atomiclabs.link.core.log.Logger
import tech.atomiclabs.link.core.port.*

class AndroidVolumeControl(
    private val audio: AudioManager, private val logger: Logger,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
) : VolumeControl {
    override suspend fun adjust(direction: VolumeDirection): PortResult<Unit> = try {
        PortResult.Success(withTimeout(5_000) { withContext(dispatcher) {
            audio.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                if (direction == VolumeDirection.UP) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER, 0)
        } })
    } catch (cause: TimeoutCancellationException) {
        currentCoroutineContext().ensureActive()
        failure(cause, FailureKind.TIMED_OUT)
    } catch (cause: CancellationException) {
        throw cause
    } catch (cause: Exception) {
        failure(cause, if (cause is SecurityException) FailureKind.PERMISSION_DENIED else FailureKind.PLATFORM)
    }

    private fun failure(cause: Exception, kind: FailureKind): PortResult.Failure {
        logger.error("AndroidVolumeControl", "adjust", "state=volume_failed", FailureDiagnostics.sanitized(cause))
        return PortResult.Failure(PortFailure(kind, cause))
    }
}
