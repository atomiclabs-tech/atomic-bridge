package tech.atomiclabs.link.core.connection

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import tech.atomiclabs.link.core.fakes.FakeLogger
import tech.atomiclabs.link.core.port.*
import tech.atomiclabs.link.core.protocol.Envelope

@OptIn(ExperimentalCoroutinesApi::class)
class OutboundStreamsTest {
    @Test fun sendFailureIsReturnedAndLoggedWithoutCancellingAnotherStream() = runTest {
        val logger = FakeLogger()
        val streams = OutboundStreams(logger)
        var result: PortResult<Unit>? = null
        var otherRunning = false
        streams.start(backgroundScope, listOf(
            OutboundStream { result = it(Envelope("notifications", "fixture", "id")) },
            OutboundStream { otherRunning = true; awaitCancellation() },
        ), 1, { true }, { throw IllegalStateException("private-event-content") })
        runCurrent()
        assertTrue(result is PortResult.Failure)
        assertTrue(otherRunning)
        assertEquals("send", logger.entries.single().operation)
        assertFalse(logger.entries.single().cause!!.stackTraceToString().contains("private-event-content"))
        streams.stop()
    }
}
