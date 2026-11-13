// Ported from upstream app/src/main/kotlin/com/hermesandroid/relay/event/EventStore.kt at commit da782a8.
package tech.atomiclabs.link.device.access

import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.CancellationException
import tech.atomiclabs.link.core.command.FailureDiagnostics
import tech.atomiclabs.link.core.events.EventBuffer
import tech.atomiclabs.link.core.log.Logger
import tech.atomiclabs.link.core.unlock.PinPadRules

/** Copies callback-local fields only; no platform event or source node is retained. */
class EventFeed(
    private val buffer: EventBuffer,
    private val logger: Logger,
    private val isKeyguardShowing: () -> Boolean = { false },
) {
    fun onEvent(event: AccessibilityEvent) {
        if (!buffer.isStreaming) return
        var eventType: Int? = null
        try {
            if (isKeyguardShowing()) return
            val capturedAt = event.eventTime
            if (buffer.suppresses(capturedAt) || event.isPassword) return
            val packageName = event.packageName?.toString()
            if (packageName == PinPadRules.systemUiPackage && sensitiveSource(event)) return
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
            buffer.append(type, packageName, event.className?.toString(),
                text, event.contentDescription?.toString(), capturedAt)
        } catch (cause: CancellationException) {
            throw cause
        } catch (cause: Exception) {
            logger.error("EventFeed", "onEvent", "state=skipped event_type=$eventType", FailureDiagnostics.sanitized(cause))
        }
    }

    private fun sensitiveSource(event: AccessibilityEvent): Boolean {
        var source = event.source
        repeat(64) {
            val node = source ?: return false
            val id = node.viewIdResourceName.orEmpty()
            val kind = node.className?.toString().orEmpty()
            if (node.isPassword || PinPadRules.sensitiveViewIdFragments.any { id.contains(it, ignoreCase = true) } ||
                PinPadRules.sensitiveClassFragments.any { kind.contains(it, ignoreCase = true) }) return true
            source = node.parent
        }
        // An unreadably deep SystemUI source cannot establish that its input is nonsensitive.
        return true
    }
}
