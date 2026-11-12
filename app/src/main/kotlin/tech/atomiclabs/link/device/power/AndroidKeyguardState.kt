package tech.atomiclabs.link.device.power

import android.app.KeyguardManager
import kotlinx.coroutines.*
import tech.atomiclabs.link.core.command.FailureDiagnostics
import tech.atomiclabs.link.core.log.Logger
import tech.atomiclabs.link.core.port.*

class AndroidKeyguardState(
    private val manager: KeyguardManager,
    private val logger: Logger,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
) : KeyguardState {
    override suspend fun read(): PortResult<KeyguardSnapshot> = try {
        PortResult.Success(withTimeout(3_000) { withContext(dispatcher) {
            KeyguardSnapshot(manager.isKeyguardLocked, manager.isKeyguardSecure)
        } })
    } catch (cause: TimeoutCancellationException) {
        failure(cause, FailureKind.TIMED_OUT)
    } catch (cause: CancellationException) {
        throw cause
    } catch (cause: Exception) {
        failure(cause, if (cause is SecurityException) FailureKind.PERMISSION_DENIED else FailureKind.PLATFORM)
    }

    private fun failure(cause: Exception, kind: FailureKind): PortResult.Failure {
        logger.error("AndroidKeyguardState", "read", "state=keyguard_read_failed", FailureDiagnostics.sanitized(cause))
        return PortResult.Failure(PortFailure(kind, cause))
    }
}
