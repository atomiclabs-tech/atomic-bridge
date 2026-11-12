// Ported from upstream app/src/main/kotlin/com/hermesandroid/relay/bridge/UnattendedAccessManager.kt at commit da782a8.
package tech.atomiclabs.link.device.power

import android.os.PowerManager
import kotlinx.coroutines.*
import tech.atomiclabs.link.core.command.FailureDiagnostics
import tech.atomiclabs.link.core.log.Logger
import tech.atomiclabs.link.core.port.*

class AndroidPowerState(
    private val manager: PowerManager,
    private val logger: Logger,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
) : PowerState {
    private var wakeLock: PowerManager.WakeLock? = null

    override suspend fun isInteractive(): PortResult<Boolean> = checked("isInteractive") { manager.isInteractive }

    @Suppress("DEPRECATION")
    override suspend fun wakeWithBrightLock(durationMs: Long): PortResult<Unit> = checked("wakeWithBrightLock") {
        require(durationMs > 0) { "Wake duration must be positive." }
        val lock = wakeLock ?: manager.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
            PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE, "link:wake").also {
            it.setReferenceCounted(false)
            wakeLock = it
        }
        lock.acquire(durationMs)
        check(lock.isHeld) { "The screen wake lock was not acquired." }
    }

    private suspend fun <T> checked(operation: String, action: () -> T): PortResult<T> = try {
        PortResult.Success(withTimeout(3_000) { withContext(dispatcher) { action() } })
    } catch (cause: TimeoutCancellationException) {
        failure(operation, cause, FailureKind.TIMED_OUT)
    } catch (cause: CancellationException) {
        throw cause
    } catch (cause: Exception) {
        failure(operation, cause, if (cause is SecurityException) FailureKind.PERMISSION_DENIED else FailureKind.PLATFORM)
    }

    private fun failure(operation: String, cause: Exception, kind: FailureKind): PortResult.Failure {
        logger.error("AndroidPowerState", operation, "state=power_failed", FailureDiagnostics.sanitized(cause))
        return PortResult.Failure(PortFailure(kind, cause,
            permission = if (kind == FailureKind.PERMISSION_DENIED && operation == "wakeWithBrightLock")
                "android.permission.WAKE_LOCK" else null))
    }
}
