package tech.atomiclabs.link.device.access

import android.view.accessibility.AccessibilityEvent
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import tech.atomiclabs.link.core.events.EventBuffer
import tech.atomiclabs.link.core.fakes.FakeClock
import tech.atomiclabs.link.core.fakes.FakeLogger

class EventFeedTest {
    @Test fun mapsExactlyFiveTypesAndCopiesJoinedTextWithoutRetainingPlatformData() = runTest {
        val buffer = EventBuffer(FakeClock(testScheduler, 1234))
        val logger = FakeLogger()
        val feed = EventFeed(buffer, logger)
        val ignoredWhileOff = mockk<AccessibilityEvent>()
        feed.onEvent(ignoredWhileOff)
        verify { ignoredWhileOff wasNot Called }
        buffer.setStreaming(true)
        val types = listOf(AccessibilityEvent.TYPE_VIEW_CLICKED, AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED)
        val mutableText = mutableListOf<CharSequence>("  first", "second  ")
        for (type in types) {
            val event = event(type)
            every { event.text } returns mutableText
            feed.onEvent(event)
            verify(exactly = 0) { event.source }
        }
        feed.onEvent(event(AccessibilityEvent.TYPE_WINDOWS_CHANGED))
        feed.onEvent(event(AccessibilityEvent.TYPE_VIEW_FOCUSED))
        mutableText.clear()
        val entries = buffer.recent().entries
        assertEquals(EventBuffer.Type.entries, entries.map { it.type })
        assertEquals(setOf("first second"), entries.map { it.text }.toSet())
        assertEquals(setOf(1234L), entries.map { it.timestamp }.toSet())
        assertEquals(setOf("example"), entries.map { it.packageName }.toSet())
        assertEquals(setOf("Field"), entries.map { it.className }.toSet())
        assertEquals(setOf("private-description"), entries.map { it.contentDescription }.toSet())
        assertTrue(logger.entries.isEmpty())
    }

    @Test fun failedTextLookupSkipsAndLogsSanitizedCauseWhileLaterSameTypeStillRecords() = runTest {
        val buffer = EventBuffer(FakeClock(testScheduler))
        buffer.setStreaming(true)
        val logger = FakeLogger()
        val feed = EventFeed(buffer, logger)
        val bad = event(AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED)
        val cause = IllegalStateException("private-event-text", SecurityException("private-nested-text"))
        every { bad.text } throws cause
        feed.onEvent(bad)
        assertTrue(buffer.recent().entries.isEmpty())
        val logged = logger.entries.single()
        assertEquals("error", logged.level); assertEquals("EventFeed", logged.component)
        assertEquals("onEvent", logged.operation)
        assertArrayEquals(cause.stackTrace, logged.cause!!.stackTrace)
        assertArrayEquals(cause.cause!!.stackTrace, logged.cause!!.cause!!.stackTrace)
        assertFalse(logged.cause!!.stackTraceToString().contains("private-"))
        assertFalse(logged.context.contains("private-"))
        feed.onEvent(event(AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED))
        assertEquals(1, buffer.recent().entries.size)
    }

    @Test fun textIsTrimmedAndCutWhileMissingFieldsRemainAbsentData() = runTest {
        val buffer = EventBuffer(FakeClock(testScheduler))
        buffer.setStreaming(true)
        val feed = EventFeed(buffer, FakeLogger())
        val long = event(AccessibilityEvent.TYPE_VIEW_CLICKED)
        every { long.text } returns mutableListOf("x".repeat(250))
        feed.onEvent(long)
        val empty = event(AccessibilityEvent.TYPE_VIEW_SCROLLED)
        every { empty.text } returns mutableListOf("  ")
        every { empty.packageName } returns null
        every { empty.className } returns null
        every { empty.contentDescription } returns null
        feed.onEvent(empty)
        assertEquals("x".repeat(200), buffer.recent().entries.first().text)
        val missing = buffer.recent().entries.last()
        assertNull(missing.text); assertNull(missing.packageName)
        assertNull(missing.className); assertNull(missing.contentDescription)
    }

    private fun event(type: Int): AccessibilityEvent = mockk<AccessibilityEvent>().also { event ->
        every { event.eventType } returns type
        every { event.packageName } returns "example"
        every { event.className } returns "Field"
        every { event.text } returns mutableListOf("private-event-text")
        every { event.contentDescription } returns "private-description"
    }
}
