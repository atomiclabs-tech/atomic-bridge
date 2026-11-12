package tech.atomiclabs.link.core.notify

import java.util.UUID
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import tech.atomiclabs.link.core.port.NotificationEvent

class NotificationFramesTest {
    @Test fun postedFrameHasExactFieldsAndOptionalCategory() {
        val event = NotificationEvent.Posted("key", "example.app", "title", "text", "sub-text", 123, true, "msg")
        val frame = NotificationFrames { "fixture-id" }.frame(event)
        assertEquals("notifications", frame.channel)
        assertEquals("notification.posted", frame.type)
        assertEquals("fixture-id", frame.id)
        assertEquals(buildJsonObject {
            put("key", "key"); put("package_name", "example.app"); put("title", "title")
            put("text", "text"); put("sub_text", "sub-text"); put("posted_at", 123L)
            put("ongoing", true); put("category", "msg")
        }, frame.payload)
        val absent = NotificationFrames().frame(event.copy(title = null, text = null, subText = null, category = null))
        assertFalse(absent.payload.containsKey("category"))
        for (field in listOf("title", "text", "sub_text")) assertEquals(JsonNull, absent.payload[field])
    }

    @Test fun removedFrameHasOnlyRemovalFieldsAndPreservesPlatformReasonWhenGiven() {
        val frames = NotificationFrames()
        val event = NotificationEvent.Removed("key", "example.app", 456, 8)
        val frame = frames.frame(event)
        assertEquals("notifications", frame.channel)
        assertEquals("notification.removed", frame.type)
        assertEquals(buildJsonObject {
            put("key", "key"); put("package_name", "example.app"); put("removed_at", 456L); put("reason", 8)
        }, frame.payload)
        assertEquals(setOf("key", "package_name", "removed_at"), frames.frame(event.copy(reason = null)).payload.keys)
    }

    @Test fun everyNewPostedOrRemovedEventGetsAFreshUuid() {
        val frames = NotificationFrames()
        val event = NotificationEvent.Removed("key", "example.app", 456)
        val ids = listOf(frames.frame(event).id, frames.frame(event).id,
            frames.frame(NotificationEvent.Posted("key", "example.app", null, null, null, 123, false)).id)
        assertEquals(3, ids.toSet().size)
        ids.forEach { assertEquals(it, UUID.fromString(it).toString()) }
    }
}
