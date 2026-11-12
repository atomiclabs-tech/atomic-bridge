package tech.atomiclabs.link.device.notify

import android.app.Notification
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import tech.atomiclabs.link.core.command.FailureDiagnostics
import tech.atomiclabs.link.core.log.Logger
import tech.atomiclabs.link.core.port.*

/** Runtime-owned source shared by the Android listener, offline feed and status reporter. */
class NotificationHub(
    private val context: Context,
    private val clock: Clock,
    private val logger: Logger,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
) : NotificationSource {
    private class Registration(val listener: (NotificationEvent) -> Unit)

    private val ownPackage = context.packageName
    private val lock = Any()
    private val registrations = mutableListOf<Registration>()
    private var service: NotificationListenerService? = null

    fun onConnected(connected: NotificationListenerService) = synchronized(lock) {
        service = connected
        logger.info("NotificationHub", "onConnected", "state=connected")
    }

    fun onDisconnected(disconnected: NotificationListenerService) = synchronized(lock) {
        if (service === disconnected) {
            service = null
            logger.info("NotificationHub", "onDisconnected", "state=disconnected")
        }
    }

    override suspend fun isEnabled(): PortResult<Boolean> = try {
        PortResult.Success(withTimeout(5_000) {
            withContext(dispatcher) {
                val enabled = ownPackage in NotificationManagerCompat.getEnabledListenerPackages(context)
                enabled && synchronized(lock) { service != null }
            }
        })
    } catch (cause: TimeoutCancellationException) {
        failure("isEnabled", cause, FailureKind.TIMED_OUT)
    } catch (cause: CancellationException) {
        throw cause
    } catch (cause: RuntimeException) {
        failure("isEnabled", cause,
            if (cause is SecurityException) FailureKind.PERMISSION_DENIED else FailureKind.PLATFORM)
    }

    override suspend fun register(listener: (NotificationEvent) -> Unit): PortResult<NotificationSubscription> {
        val registration = Registration(listener)
        synchronized(lock) { registrations += registration }
        return PortResult.Success(NotificationSubscription {
            synchronized(lock) { registrations.remove(registration) }
            PortResult.Success(Unit)
        })
    }

    fun onPosted(owner: NotificationListenerService, notification: StatusBarNotification?) =
        publish(owner, notification, "posted") { posted ->
            val contents = requireNotNull(posted.notification) { "Notification contents are unavailable." }
            val extras = requireNotNull(contents.extras) { "Notification fields are unavailable." }
            NotificationEvent.Posted(
                posted.key, posted.packageName,
                extras.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
                extras.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
                extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString(),
                posted.postTime, posted.isOngoing, contents.category,
            )
        }

    fun onRemoved(owner: NotificationListenerService, notification: StatusBarNotification?, reason: Int? = null) =
        publish(owner, notification, "removed") {
            NotificationEvent.Removed(it.key, it.packageName, clock.epochMs(), reason)
        }

    private fun publish(owner: NotificationListenerService, raw: StatusBarNotification?, operation: String,
        read: (StatusBarNotification) -> NotificationEvent) = synchronized(lock) {
        if (service !== owner) return@synchronized
        val event = try {
            val notification = requireNotNull(raw) { "The notification callback has no notification." }
            if (notification.packageName == ownPackage) return@synchronized
            read(notification)
        } catch (cause: RuntimeException) {
            failure(operation, cause, FailureKind.PLATFORM)
            return@synchronized
        }
        // Holding the same lock as close prevents a callback from beginning after close returns.
        for (registration in registrations.toList()) {
            if (registration !in registrations) continue
            try { registration.listener(event) }
            catch (cause: RuntimeException) { failure("${operation}Callback", cause, FailureKind.PLATFORM) }
        }
    }

    private fun failure(operation: String, cause: RuntimeException, kind: FailureKind): PortResult.Failure {
        logger.error("NotificationHub", operation, "state=failed", FailureDiagnostics.sanitized(cause))
        return PortResult.Failure(PortFailure(kind, cause))
    }
}
