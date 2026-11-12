// Adapted from upstream app/src/main/kotlin/com/hermesandroid/relay/accessibility/ActionExecutor.kt at da782a8.
package tech.atomiclabs.link.device.phone

import android.content.Context
import android.media.AudioManager
import android.view.KeyEvent
import kotlinx.coroutines.*
import tech.atomiclabs.link.core.command.FailureDiagnostics
import tech.atomiclabs.link.core.log.Logger
import tech.atomiclabs.link.core.port.*

class AndroidMediaKeys(
    private val context: Context,
    private val logger: Logger,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
) : MediaKeys {
    override suspend fun send(action: MediaAction): PortResult<Int> = try {
        withTimeout(5_000) { withContext(dispatcher) {
            val audio = checkNotNull(context.getSystemService(AudioManager::class.java)) {
                "The audio service is unavailable."
            }
            val keycode = when (action) {
                MediaAction.PLAY -> KeyEvent.KEYCODE_MEDIA_PLAY
                MediaAction.PAUSE -> KeyEvent.KEYCODE_MEDIA_PAUSE
                MediaAction.TOGGLE -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                MediaAction.NEXT -> KeyEvent.KEYCODE_MEDIA_NEXT
                MediaAction.PREVIOUS -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            }
            for (event in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
                currentCoroutineContext().ensureActive()
                audio.dispatchMediaKeyEvent(KeyEvent(event, keycode))
            }
            // The public dispatcher returns void; completion does not acknowledge playback state.
            PortResult.Success(keycode)
        } }
    } catch (cause: TimeoutCancellationException) {
        failed(cause, FailureKind.TIMED_OUT)
    } catch (cause: CancellationException) {
        throw cause
    } catch (cause: SecurityException) {
        failed(cause, FailureKind.PERMISSION_DENIED)
    } catch (cause: Exception) {
        failed(cause, FailureKind.PLATFORM)
    }

    private fun failed(cause: Exception, kind: FailureKind): PortResult.Failure {
        logger.error("AndroidMediaKeys", "send", "state=failed", FailureDiagnostics.sanitized(cause))
        return PortResult.Failure(PortFailure(kind, cause))
    }
}
