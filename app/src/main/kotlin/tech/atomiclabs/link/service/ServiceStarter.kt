package tech.atomiclabs.link.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import tech.atomiclabs.link.core.command.FailureDiagnostics
import tech.atomiclabs.link.core.log.Logger

class ServiceStarter(
    private val context: Context,
    private val logger: Logger,
    private val startRuntime: () -> Unit,
) {
    fun start() {
        try {
            checkNotNull(context.startForegroundService(Intent(context, ConnectionService::class.java))) {
                "The foreground service start was refused."
            }
        } catch (cause: Exception) {
            logger.error("ServiceStarter", "startForegroundService", "state=refused", FailureDiagnostics.sanitized(cause))
        } finally { startRuntime() }
    }

    companion object {
        fun start(context: Context) {
            val app = context.applicationContext as LinkApp
            app.runtime.requestStart()
        }
    }
}

/** The service's tested lifecycle owner keeps the approved permanent lock until destruction. */
internal class ConnectionServiceLifetime(
    private val notification: ServiceNotification,
    private val power: PowerManager,
    private val logger: Logger,
    private val availability: (Service?) -> Unit,
    private val startRuntime: () -> Boolean,
) {
    private var lock: PowerManager.WakeLock? = null

    fun created(service: Service) {
        var stopRequested = false
        try {
            check(lock == null) { "The connection service is already created." }
            val running = notification.build()
            try {
                service.startForeground(ServiceNotification.ID, running,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } catch (refusal: Exception) {
                logger.error("ConnectionService", "startForeground", "state=refused",
                    FailureDiagnostics.sanitized(refusal))
                // End the unpromoted start before Android's foreground-service deadline; Runtime owns the connection.
                availability(null)
                service.stopSelf()
                stopRequested = true
                return
            }
            val acquired = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AtomicLink:running")
            lock = acquired
            acquired.setReferenceCounted(false)
            // R16 explicitly approves a permanent partial wake lock while this service runs.
            acquired.acquire()
            check(acquired.isHeld) { "The connection wake lock was not acquired." }
            availability(service)
        } catch (cause: Exception) {
            logger.error("ConnectionService", "create", "state=failed", FailureDiagnostics.sanitized(cause))
            try { destroyed() }
            catch (cleanup: Exception) { cause.addSuppressed(cleanup) }
            throw IllegalStateException("The connection service could not start.", cause)
        } finally {
            if (!startRuntime()) {
                logger.warn("ConnectionService", "create", "state=killed")
                try { destroyed() }
                finally { if (!stopRequested) service.stopSelf() }
            }
        }
    }

    fun destroyed() {
        availability(null)
        val held = lock ?: return
        lock = null
        try {
            if (held.isHeld) {
                held.release()
                check(!held.isHeld) { "The connection wake lock was not released." }
            }
        } catch (cause: Exception) {
            logger.error("ConnectionService", "destroy", "state=failed", FailureDiagnostics.sanitized(cause))
            throw IllegalStateException("The connection wake lock could not be released.", cause)
        }
    }
}
