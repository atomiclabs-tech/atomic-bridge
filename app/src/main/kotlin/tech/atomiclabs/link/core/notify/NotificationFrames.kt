package tech.atomiclabs.link.core.notify

import java.util.UUID
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import tech.atomiclabs.link.core.port.NotificationEvent
import tech.atomiclabs.link.core.protocol.Envelope

/** Assigns one frame id per event; retransmission retains that frame and its id. */
class NotificationFrames(private val newId: () -> String = { UUID.randomUUID().toString() }) {
    fun frame(event: NotificationEvent): Envelope = Envelope(
        "notifications",
        when (event) {
            is NotificationEvent.Posted -> "notification.posted"
            is NotificationEvent.Removed -> "notification.removed"
        },
        newId(),
        buildJsonObject {
            put("key", event.key)
            put("package_name", event.packageName)
            when (event) {
                is NotificationEvent.Posted -> {
                    put("title", event.title)
                    put("text", event.text)
                    put("sub_text", event.subText)
                    put("posted_at", event.postedAt)
                    put("ongoing", event.ongoing)
                    event.category?.let { put("category", it) }
                }
                is NotificationEvent.Removed -> {
                    put("removed_at", event.removedAt)
                    event.reason?.let { put("reason", it) }
                }
            }
        },
    )
}
