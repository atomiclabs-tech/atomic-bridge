package tech.atomiclabs.link.core.command

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CommandDeadlineTest {
    @Test fun defaultDeadlineCancelsAtExactlyTwentyFiveSeconds() = runTest {
        var cancelled = false
        val work = async {
            try { CommandDeadline().run { delay(30_000) } }
            catch (cause: TimeoutCancellationException) { cancelled = true }
        }
        runCurrent()
        advanceTimeBy(24_999)
        assertFalse(cancelled)
        advanceTimeBy(1)
        runCurrent()
        assertTrue(cancelled)
        work.await()
        assertEquals(25_000, currentTime)
    }

    @Test fun returnsEarlyResultsAndRejectsInvalidBudgets() = runTest {
        assertEquals("done", CommandDeadline().run { delay(50); "done" })
        assertEquals(50, currentTime)
        assertThrows(IllegalArgumentException::class.java) { CommandDeadline(0) }
        assertThrows(IllegalArgumentException::class.java) { CommandDeadline(-1) }
    }
}
