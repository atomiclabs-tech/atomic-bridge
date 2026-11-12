package tech.atomiclabs.link.service

import android.app.*
import android.content.Context
import android.content.Intent
import tech.atomiclabs.link.R
import tech.atomiclabs.link.core.command.FailureDiagnostics
import tech.atomiclabs.link.core.log.Logger
import tech.atomiclabs.link.ui.StatusActivity

class ServiceNotification(private val context: Context, private val logger: Logger) {
    @Suppress("DEPRECATION")
    fun build(): Notification = try {
        val manager = checkNotNull(context.getSystemService(NotificationManager::class.java))
        val channel = NotificationChannel(CHANNEL, context.getString(R.string.app_name), NotificationManager.IMPORTANCE_MIN)
        channel.setSound(null, null)
        channel.enableVibration(false)
        channel.setShowBadge(false)
        channel.lockscreenVisibility = Notification.VISIBILITY_SECRET
        manager.createNotificationChannel(channel)
        val opened = PendingIntent.getActivity(context, 0, Intent(context, StatusActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(context.getString(R.string.service_running))
            .setContentIntent(opened).setOngoing(true).setDefaults(0).setSound(null).setVibrate(longArrayOf())
            .setVisibility(Notification.VISIBILITY_SECRET).setShowWhen(false).build()
    } catch (cause: Exception) {
        logger.error("ServiceNotification", "build", "state=failed", FailureDiagnostics.sanitized(cause))
        throw IllegalStateException("The running notification could not be created.", cause)
    }

    companion object { const val ID = 1; const val CHANNEL = "running" }
}
