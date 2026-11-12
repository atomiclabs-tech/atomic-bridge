// forge-allow: no-test declarations only
package tech.atomiclabs.link.core.port

/** Enabled means both Settings access and a connected listener. Events remain in memory, never logs. */
interface NotificationSource {
    suspend fun isEnabled(): PortResult<Boolean>
    suspend fun register(listener: (NotificationEvent) -> Unit): PortResult<NotificationSubscription>
}

/** Closing unregisters this listener; callbacks must not continue after close returns. */
fun interface NotificationSubscription { fun close(): PortResult<Unit> }

sealed interface NotificationEvent {
    val key: String
    val packageName: String

    data class Posted(
        override val key: String,
        override val packageName: String,
        val title: String?,
        val text: String?,
        val subText: String?,
        val postedAt: Long,
        val ongoing: Boolean,
        val category: String? = null,
    ) : NotificationEvent

    data class Removed(
        override val key: String,
        override val packageName: String,
        val removedAt: Long,
        val reason: Int? = null,
    ) : NotificationEvent
}
