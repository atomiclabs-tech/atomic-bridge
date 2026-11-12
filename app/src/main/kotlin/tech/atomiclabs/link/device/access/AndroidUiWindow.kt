package tech.atomiclabs.link.device.access

import android.graphics.Rect
import android.view.accessibility.AccessibilityWindowInfo
import kotlinx.coroutines.*
import tech.atomiclabs.link.core.command.FailureDiagnostics
import tech.atomiclabs.link.core.log.Logger
import tech.atomiclabs.link.core.port.*

class AndroidUiWindow(
    private val window: AccessibilityWindowInfo,
    private val logger: Logger,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
) {
    suspend fun snapshot(): PortResult<UiWindow> {
        var id: Int? = null
        return try {
            PortResult.Success(withTimeout(5_000) { withContext(dispatcher) {
                id = window.id
                val root = window.root
                val rect = Rect()
                window.getBoundsInScreen(rect)
                UiWindow(requireNotNull(id), type(window.type), window.type, window.layer,
                    window.isActive, window.isFocused, root?.let { AndroidUiNode(it, logger, dispatcher) },
                    Bounds(rect.left, rect.top, rect.right, rect.bottom))
            } })
        } catch (cause: TimeoutCancellationException) {
            failure(id, cause, FailureKind.TIMED_OUT)
        } catch (cause: CancellationException) {
            throw cause
        } catch (cause: Exception) {
            failure(id, cause, if (cause is SecurityException) FailureKind.PERMISSION_DENIED else FailureKind.PLATFORM)
        }
    }

    private fun failure(id: Int?, cause: Exception, kind: FailureKind): PortResult.Failure {
        logger.error("AndroidUiWindow", "snapshot", "window_id=$id state=read_failed",
            FailureDiagnostics.sanitized(cause))
        return PortResult.Failure(PortFailure(kind, cause))
    }

    private fun type(value: Int): WindowType = when (value) {
        AccessibilityWindowInfo.TYPE_APPLICATION -> WindowType.APPLICATION
        AccessibilityWindowInfo.TYPE_INPUT_METHOD -> WindowType.INPUT_METHOD
        AccessibilityWindowInfo.TYPE_SYSTEM -> WindowType.SYSTEM
        AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY -> WindowType.ACCESSIBILITY_OVERLAY
        AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER -> WindowType.SPLIT_SCREEN_DIVIDER
        else -> WindowType.OTHER
    }
}
