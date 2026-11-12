package tech.atomiclabs.link.service

import android.app.Service
import android.content.pm.ServiceInfo
import kotlinx.coroutines.*
import tech.atomiclabs.link.core.command.FailureDiagnostics
import tech.atomiclabs.link.core.log.Logger
import tech.atomiclabs.link.core.port.*

/** Temporarily promotes the existing connection service; it never starts or stops a service. */
class MicrophoneForeground(
    private val notification: ServiceNotification,
    private val logger: Logger,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
) : AudioForeground {
    private data class Attempt(val service: Service, val previousType: Int)
    private val lock = Any()
    private var service: Service? = null
    private var attempt: Attempt? = null

    fun available(current: Service?) = synchronized(lock) {
        if (service !== current) attempt = null
        service = current
    }

    override suspend fun startMicrophone(): PortResult<MicrophoneForegroundOutcome> = perform("start") {
        val current = service ?: throw IllegalStateException("The connection foreground service is unavailable.")
        check(attempt == null) { "A microphone foreground attempt is already active." }
        val previous = current.foregroundServiceType
        check(previous and ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE != 0) {
            "The connection service is not in its running foreground state."
        }
        attempt = Attempt(current, previous)
        current.startForeground(ServiceNotification.ID, notification.build(), previous or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        check(current.foregroundServiceType and ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE != 0) {
            "The microphone foreground service type was not applied."
        }
        MicrophoneForegroundOutcome(true)
    }

    override suspend fun stopMicrophone(): PortResult<Unit> = perform("stop") {
        val owned = attempt
        if (owned != null) {
            check(service === owned.service) { "The connection service changed during the microphone attempt." }
            if (owned.service.foregroundServiceType != owned.previousType) {
                owned.service.startForeground(ServiceNotification.ID, notification.build(), owned.previousType)
                check(owned.service.foregroundServiceType == owned.previousType) {
                    "The previous foreground service type was not restored."
                }
            }
            attempt = null
        }
    }

    private suspend fun <T> perform(operation: String, block: () -> T): PortResult<T> = try {
        withTimeout(3_000) { withContext(dispatcher) { synchronized(lock) { PortResult.Success(block()) } } }
    } catch (cause: TimeoutCancellationException) {
        currentCoroutineContext().ensureActive()
        failure(operation, cause, FailureKind.TIMED_OUT)
    } catch (cause: SecurityException) {
        failure(operation, cause, FailureKind.PERMISSION_DENIED)
    } catch (cause: IllegalArgumentException) {
        failure(operation, cause, FailureKind.PLATFORM)
    } catch (cause: IllegalStateException) {
        failure(operation, cause, FailureKind.UNAVAILABLE)
    }

    private fun failure(operation: String, cause: Exception, kind: FailureKind): PortResult.Failure {
        logger.error("MicrophoneForeground", operation, "state=failed", FailureDiagnostics.sanitized(cause))
        return PortResult.Failure(PortFailure(kind, cause))
    }
}
