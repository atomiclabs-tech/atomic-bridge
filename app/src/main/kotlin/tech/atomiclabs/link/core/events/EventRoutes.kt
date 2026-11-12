package tech.atomiclabs.link.core.events

import kotlinx.serialization.json.*
import tech.atomiclabs.link.core.command.*

class EventRoutes(private val buffer: EventBuffer) : RouteGroup {
    override val routes = listOf(
        Route("GET", "/events", { RouteNeeds(accessibility = true) }) { body ->
            val limit = body.int("limit", 50).coerceIn(1, EventBuffer.CAPACITY)
            val snapshot = buffer.recent(limit, body.long("since", 0))
            CommandResult.Success(buildJsonObject {
                put("entries", buildJsonArray { snapshot.entries.forEach { entry -> add(entryJson(entry)) } })
                put("count", snapshot.entries.size); put("streaming", snapshot.streaming)
            })
        },
        Route("POST", "/events/stream", { RouteNeeds(accessibility = true) }) { body ->
            val value = body.element("enabled") as? JsonPrimitive ?: throw BadRequestException("enabled")
            if (value.isString) throw BadRequestException("enabled")
            val enabled = value.booleanOrNull ?: throw BadRequestException("enabled")
            buffer.setStreaming(enabled)
            CommandResult.Success(buildJsonObject { put("streaming", enabled); put("buffer_cleared", true) })
        },
    )

    private fun entryJson(entry: EventBuffer.Entry) = buildJsonObject {
        put("timestamp", entry.timestamp); put("event_type", entry.type.wireName)
        put("package_name", entry.packageName); put("class_name", entry.className)
        put("text", entry.text); put("content_description", entry.contentDescription); put("source", entry.type.source)
    }
}
