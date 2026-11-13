package tech.atomiclabs.link.device.access

import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
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

    @Test fun keyguardAndPasswordCallbacksAreRefusedBeforeCopyingTheirContent() = runTest {
        val buffer = EventBuffer(FakeClock(testScheduler)).also { it.setStreaming(true) }
        var showing = true
        val logger = FakeLogger()
        val feed = EventFeed(buffer, logger) { showing }
        val locked = mockk<AccessibilityEvent>()
        feed.onEvent(locked)
        verify { locked wasNot Called }
        showing = false
        val password = event(AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED)
        every { password.isPassword } returns true
        feed.onEvent(password)
        verify(exactly = 0) { password.text; password.contentDescription }
        assertTrue(buffer.recent().entries.isEmpty())
        assertTrue(logger.entries.isEmpty())
    }

    @Test fun lateBouncerSourceIsRefusedWhileAnOrdinaryUnlockedSystemUiSourceStillRecords() = runTest {
        val buffer = EventBuffer(FakeClock(testScheduler)).also { it.setStreaming(true) }
        val logger = FakeLogger()
        val feed = EventFeed(buffer, logger)
        val bouncer = event(AccessibilityEvent.TYPE_VIEW_CLICKED)
        every { bouncer.packageName } returns "com.android.systemui"
        val source = mockk<AccessibilityNodeInfo>()
        every { bouncer.source } returns source
        every { source.isPassword } returns false
        every { source.viewIdResourceName } returns "com.android.systemui:id/key_enter"
        every { source.className } returns "android.widget.Button"
        feed.onEvent(bouncer)
        verify(exactly = 0) { bouncer.text; bouncer.contentDescription }
        assertTrue(buffer.recent().entries.isEmpty())
        val ordinary = event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
        every { ordinary.packageName } returns "com.android.systemui"
        every { ordinary.source } returns null
        feed.onEvent(ordinary)
        assertEquals(1, buffer.recent().entries.size)
    }

    @Test fun aFailedKeyguardReadFailsClosedAndLogsOnlyASanitizedCause() = runTest {
        val buffer = EventBuffer(FakeClock(testScheduler)).also { it.setStreaming(true) }
        val logger = FakeLogger()
        val feed = EventFeed(buffer, logger) { throw IllegalStateException("private callback failure") }
        val event = mockk<AccessibilityEvent>()
        feed.onEvent(event)
        verify { event wasNot Called }
        assertTrue(buffer.recent().entries.isEmpty())
        assertEquals("error", logger.entries.single().level)
        assertFalse(logger.entries.single().cause!!.stackTraceToString().contains("private callback failure"))
    }

    private fun event(type: Int): AccessibilityEvent = mockk<AccessibilityEvent>().also { event ->
        every { event.eventType } returns type
        every { event.eventTime } returns 0L
        every { event.isPassword } returns false
        every { event.packageName } returns "example"
        every { event.className } returns "Field"
        every { event.text } returns mutableListOf("private-event-text")
        every { event.contentDescription } returns "private-description"
    }
}
