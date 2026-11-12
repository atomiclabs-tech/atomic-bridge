// Ported from upstream app/src/main/kotlin/com/hermesandroid/relay/event/EventStore.kt at commit da782a8.
package tech.atomiclabs.link.device.access

import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.CancellationException
import tech.atomiclabs.link.core.command.FailureDiagnostics
import tech.atomiclabs.link.core.events.EventBuffer
import tech.atomiclabs.link.core.log.Logger

/** Copies callback-local fields only; no platform event or source node is retained. */
class EventFeed(private val buffer: EventBuffer, private val logger: Logger) {
    fun onEvent(event: AccessibilityEvent) {
        if (!buffer.isStreaming) return
        var eventType: Int? = null
        try {
            eventType = event.eventType
            val type = when (eventType) {
                AccessibilityEvent.TYPE_VIEW_CLICKED -> EventBuffer.Type.CLICK
                AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> EventBuffer.Type.TEXT_CHANGED
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> EventBuffer.Type.WINDOW_CONTENT_CHANGED
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> EventBuffer.Type.WINDOW_STATE_CHANGED
                AccessibilityEvent.TYPE_VIEW_SCROLLED -> EventBuffer.Type.SCROLL
                else -> return
            }
            val text = event.text?.joinToString(" ") { it?.toString().orEmpty() }?.trim()?.ifEmpty { null }
            buffer.append(type, event.packageName?.toString(), event.className?.toString(),
                text, event.contentDescription?.toString())
        } catch (cause: CancellationException) {
            throw cause
        } catch (cause: Exception) {
            logger.error("EventFeed", "onEvent", "state=skipped event_type=$eventType", FailureDiagnostics.sanitized(cause))
        }
    }
}
