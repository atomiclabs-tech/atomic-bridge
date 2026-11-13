package tech.atomiclabs.link.core.events

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import tech.atomiclabs.link.core.fakes.FakeClock

@OptIn(ExperimentalCoroutinesApi::class)
class EventBufferTest {
    @Test fun recordingStartsOffAndEveryStreamSettingClearsHistoryAndThrottle() = runTest {
        val buffer = EventBuffer(FakeClock(testScheduler))
        fun append() = buffer.append(EventBuffer.Type.CLICK, "app", null, "text", null)
        append()
        assertFalse(buffer.recent().streaming); assertTrue(buffer.recent().entries.isEmpty())
        buffer.setStreaming(true); append()
        assertEquals(1, buffer.recent().entries.size)
        buffer.setStreaming(true)
        assertTrue(buffer.recent().entries.isEmpty())
        append()
        assertEquals(1, buffer.recent().entries.size)
        buffer.setStreaming(false); append()
        assertFalse(buffer.recent().streaming); assertTrue(buffer.recent().entries.isEmpty())
        buffer.setStreaming(true)
        assertTrue(buffer.recent().entries.isEmpty())
    }

    @Test fun sensitiveCapturePurgesHistorySurvivesStreamTogglesAndRejectsLateCallbacksOnItsOwnClock() = runTest {
        val clock = FakeClock(testScheduler)
        var uptime = 10L
        val buffer = EventBuffer(clock) { uptime }
        buffer.setStreaming(true)
        fun append(at: Long = uptime) = buffer.append(EventBuffer.Type.CLICK, "app", null, "ordinary", null, at)
        append()
        buffer.beginSensitiveInput()
        assertTrue(buffer.recent().entries.isEmpty())
        buffer.setStreaming(true)
        append()
        assertTrue(buffer.recent().entries.isEmpty())
        advanceTimeBy(100_000) // Elapsed time can diverge from Android's event uptime across deep sleep.
        uptime = 20
        buffer.endSensitiveInput()
        append(15)
        append(20)
        assertTrue(buffer.recent().entries.isEmpty())
        uptime = 21
        append()
        assertEquals(1, buffer.recent().entries.size)
        assertTrue(buffer.recent().streaming)
    }

    @Test fun nestedSensitiveScopesKeepCaptureClosedUntilTheLastScopeEnds() = runTest {
        val buffer = EventBuffer(FakeClock(testScheduler))
        buffer.setStreaming(true)
        buffer.beginSensitiveInput(); buffer.beginSensitiveInput()
        buffer.endSensitiveInput()
        advanceTimeBy(1)
        assertTrue(buffer.suppresses(currentTime))
        buffer.endSensitiveInput()
        advanceTimeBy(1)
        assertFalse(buffer.suppresses(currentTime))
    }

    @Test fun throttleIsPerTypeAndPackageAndAcceptsTheHundredMillisecondBoundary() = runTest {
        val clock = FakeClock(testScheduler, 1000)
        val buffer = EventBuffer(clock)
        buffer.setStreaming(true)
        fun append(type: EventBuffer.Type = EventBuffer.Type.CLICK, pkg: String = "app") =
            buffer.append(type, pkg, null, null, null)
        append()
        advanceTimeBy(99)
        clock.originEpochMs += 10_000
        append()
        assertEquals(1, buffer.recent().entries.size)
        append(pkg = "other"); append(EventBuffer.Type.SCROLL)
        advanceTimeBy(1); append()
        val entries = buffer.recent().entries
        assertEquals(4, entries.size)
        assertEquals(listOf(1000L, 11099L, 11099L, 11100L), entries.map { it.timestamp })
    }

    @Test fun capacityDropsOldestAndOnlyTextIsCutToTwoHundredCharacters() = runTest {
        val buffer = EventBuffer(FakeClock(testScheduler))
        buffer.setStreaming(true)
        repeat(501) { buffer.append(EventBuffer.Type.TEXT_CHANGED, "app$it", "Field", "t".repeat(250), "d".repeat(250)) }
        val entries = buffer.recent(500).entries
        assertEquals(500, entries.size)
        assertEquals("app1", entries.first().packageName); assertEquals("app500", entries.last().packageName)
        assertEquals("t".repeat(200), entries.last().text)
        assertEquals("d".repeat(250), entries.last().contentDescription)
    }

    @Test fun snapshotsReturnNewestLimitInChronologicalOrderWithExclusiveSince() = runTest {
        val buffer = EventBuffer(FakeClock(testScheduler, 1000))
        buffer.setStreaming(true)
        repeat(60) {
            buffer.append(EventBuffer.Type.CLICK, "app$it", null, null, null)
            advanceTimeBy(100)
        }
        assertEquals(50, buffer.recent().entries.size)
        assertEquals("app10", buffer.recent().entries.first().packageName)
        assertEquals(listOf("app58", "app59"), buffer.recent(2).entries.map { it.packageName })
        assertEquals(listOf("app59"), buffer.recent(500, 6800).entries.map { it.packageName })
        assertTrue(buffer.recent(500, 6900).entries.isEmpty())
    }
}
