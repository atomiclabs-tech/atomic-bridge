// Ported from upstream app/src/main/kotlin/com/hermesandroid/relay/accessibility/ActionExecutor.kt at commit da782a8.
package tech.atomiclabs.link.device.access

import android.accessibilityservice.AccessibilityService
import kotlinx.coroutines.*
import tech.atomiclabs.link.core.command.FailureDiagnostics
import tech.atomiclabs.link.core.log.Logger
import tech.atomiclabs.link.core.port.*

class AccessibilityGlobalKeys(
    private val hub: AccessibilityHub, private val logger: Logger,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
) : GlobalKeys {
    override suspend fun perform(action: GlobalAction): PortResult<Unit> {
        val current = hub.currentService()
        if (current is PortResult.Failure) return current
        return try {
            withTimeout(5_000) { withContext(dispatcher) {
                val id = when (action) {
                    GlobalAction.BACK -> AccessibilityService.GLOBAL_ACTION_BACK
                    GlobalAction.HOME -> AccessibilityService.GLOBAL_ACTION_HOME
                    GlobalAction.RECENTS -> AccessibilityService.GLOBAL_ACTION_RECENTS
                    GlobalAction.NOTIFICATIONS -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
                    GlobalAction.QUICK_SETTINGS -> AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
                    GlobalAction.POWER_DIALOG -> AccessibilityService.GLOBAL_ACTION_POWER_DIALOG
                    GlobalAction.LOCK_SCREEN -> AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN
                }
                if ((current as PortResult.Success).value.performGlobalAction(id)) PortResult.Success(Unit)
                else failure(IllegalStateException("The global action was refused."), FailureKind.REJECTED)
            } }
        } catch (cause: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            failure(cause, FailureKind.TIMED_OUT)
        } catch (cause: CancellationException) {
            throw cause
        } catch (cause: Exception) {
            failure(cause, if (cause is SecurityException) FailureKind.PERMISSION_DENIED else FailureKind.PLATFORM)
        }
    }

    private fun failure(cause: Exception, kind: FailureKind): PortResult.Failure {
        logger.error("AccessibilityGlobalKeys", "perform", "state=key_failed", FailureDiagnostics.sanitized(cause))
        return PortResult.Failure(PortFailure(kind, cause))
    }
}
