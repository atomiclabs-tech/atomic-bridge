// Ported from upstream app/src/main/kotlin/com/hermesandroid/relay/event/EventStore.kt at commit da782a8.
package tech.atomiclabs.link.core.events

import tech.atomiclabs.link.core.port.Clock

/** Memory-only stream history; transitions, throttling and snapshots share one lock. */
class EventBuffer(private val clock: Clock) {
    enum class Type(val wireName: String, val source: String) {
        CLICK("click", "TYPE_VIEW_CLICKED"),
        TEXT_CHANGED("text_changed", "TYPE_VIEW_TEXT_CHANGED"),
        WINDOW_CONTENT_CHANGED("window_content_changed", "TYPE_WINDOW_CONTENT_CHANGED"),
        WINDOW_STATE_CHANGED("window_state_changed", "TYPE_WINDOW_STATE_CHANGED"),
        SCROLL("scroll", "TYPE_VIEW_SCROLLED"),
    }

    data class Entry(
        val timestamp: Long, val type: Type, val packageName: String?, val className: String?,
        val text: String?, val contentDescription: String?,
    )
    data class Snapshot(val entries: List<Entry>, val streaming: Boolean)

    @Volatile var isStreaming = false
        private set
    private val entries = ArrayDeque<Entry>()
    private val lastRecorded = mutableMapOf<Pair<Type, String?>, Long>()

    @Synchronized fun setStreaming(enabled: Boolean) {
        isStreaming = enabled
        entries.clear()
        lastRecorded.clear()
    }

    @Synchronized fun append(type: Type, packageName: String?, className: String?,
        text: String?, contentDescription: String?) {
        if (!isStreaming) return
        val now = clock.monotonicMs()
        lastRecorded.entries.removeAll { now - it.value >= THROTTLE_MS }
        val key = type to packageName
        if (lastRecorded.containsKey(key)) return
        lastRecorded[key] = now
        if (entries.size == CAPACITY) entries.removeFirst()
        entries.addLast(Entry(clock.epochMs(), type, packageName, className, text?.take(200), contentDescription))
    }

    @Synchronized fun recent(limit: Int = 50, since: Long = 0): Snapshot = Snapshot(
        entries.filter { since <= 0 || it.timestamp > since }.takeLast(limit.coerceIn(1, CAPACITY)), isStreaming,
    )

    companion object {
        const val CAPACITY = 500
        private const val THROTTLE_MS = 100L
    }
}
