// forge-allow: no-test Android service entry point only forwards to the runtime-owned tested NotificationHub.
package tech.atomiclabs.link.device.notify

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class LinkNotificationListener : NotificationListenerService() {
    override fun onListenerConnected() {
        super.onListenerConnected()
        hub.onConnected(this)
    }

    override fun onListenerDisconnected() {
        hub.onDisconnected(this)
        super.onListenerDisconnected()
    }

    override fun onDestroy() {
        hub.onDisconnected(this)
        super.onDestroy()
    }

    override fun onNotificationPosted(notification: StatusBarNotification?) = hub.onPosted(this, notification)

    override fun onNotificationPosted(notification: StatusBarNotification?, rankingMap: RankingMap?) =
        hub.onPosted(this, notification)

    override fun onNotificationRemoved(notification: StatusBarNotification?) = hub.onRemoved(this, notification)

    override fun onNotificationRemoved(notification: StatusBarNotification?, rankingMap: RankingMap?) =
        hub.onRemoved(this, notification)

    override fun onNotificationRemoved(notification: StatusBarNotification?, rankingMap: RankingMap?, reason: Int) =
        hub.onRemoved(this, notification, reason)

    companion object {
        lateinit var hub: NotificationHub
    }
}
