package tech.atomiclabs.link.core.command

import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test
import tech.atomiclabs.link.core.protocol.BridgeResponse

class RequestDeduperTest {
    private fun response(id: String) = BridgeResponse(id, buildJsonObject { put("success", true) })

    @Test fun simultaneousReservationsShareOnePendingResponseThenReplayIt() = runTest {
        val deduper = RequestDeduper()
        val owner = deduper.reserve("one") as RequestDeduper.Ticket.Owner
        val duplicate = deduper.reserve("one") as RequestDeduper.Ticket.Pending
        val waiter = async { duplicate.response.await() }
        val first = response("one")
        deduper.complete(owner, first)
        assertSame(first, waiter.await())
        assertSame(first, (deduper.reserve("one") as RequestDeduper.Ticket.Replay).response)
    }

    @Test fun remembersExactlyTheLast256CompletedRequestsWithoutEvictingInFlightRequests() = runTest {
        val deduper = RequestDeduper()
        val pending = deduper.reserve("pending") as RequestDeduper.Ticket.Owner
        for (index in 0..256) {
            val id = "request-$index"
            deduper.complete(deduper.reserve(id) as RequestDeduper.Ticket.Owner, response(id))
        }
        assertTrue(deduper.reserve("request-0") is RequestDeduper.Ticket.Owner)
        assertTrue(deduper.reserve("request-1") is RequestDeduper.Ticket.Replay)
        assertTrue(deduper.reserve("request-256") is RequestDeduper.Ticket.Replay)
        assertTrue(deduper.reserve("pending") is RequestDeduper.Ticket.Pending)
        deduper.complete(pending, response("pending"))
        assertTrue(deduper.reserve("pending") is RequestDeduper.Ticket.Replay)
    }

    @Test fun invalidCompletionDoesNotLoseThePendingOwnerAndDoubleCompletionIsRejected() = runTest {
        val deduper = RequestDeduper()
        val owner = deduper.reserve("one") as RequestDeduper.Ticket.Owner
        var mismatch: Exception? = null
        try { deduper.complete(owner, response("two")) } catch (cause: IllegalArgumentException) { mismatch = cause }
        assertNotNull(mismatch)
        assertTrue(deduper.reserve("one") is RequestDeduper.Ticket.Pending)
        deduper.complete(owner, response("one"))
        var duplicate: Exception? = null
        try { deduper.complete(owner, response("one")) } catch (cause: IllegalStateException) { duplicate = cause }
        assertNotNull(duplicate)
        assertThrows(IllegalArgumentException::class.java) { RequestDeduper(0) }
    }
}
