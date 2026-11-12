package tech.atomiclabs.link.core.command

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tech.atomiclabs.link.core.protocol.BridgeResponse

/** Keeps the last 256 completed responses; in-flight owners are retained until completion. */
class RequestDeduper(private val capacity: Int = 256) {
    private val mutex = Mutex()
    private val completed = LinkedHashMap<String, BridgeResponse>()
    private val pending = mutableMapOf<String, CompletableDeferred<BridgeResponse>>()

    init { require(capacity > 0) { "Replay capacity must be positive." } }

    sealed interface Ticket {
        class Owner internal constructor(
            val requestId: String,
            internal val response: CompletableDeferred<BridgeResponse>,
        ) : Ticket
        data class Replay(val response: BridgeResponse) : Ticket
        data class Pending(val response: Deferred<BridgeResponse>) : Ticket
    }

    suspend fun reserve(requestId: String): Ticket = mutex.withLock {
        completed[requestId]?.let { return@withLock Ticket.Replay(it) }
        pending[requestId]?.let { return@withLock Ticket.Pending(it) }
        val response = CompletableDeferred<BridgeResponse>()
        pending[requestId] = response
        Ticket.Owner(requestId, response)
    }

    suspend fun complete(owner: Ticket.Owner, response: BridgeResponse) = mutex.withLock {
        check(pending[owner.requestId] === owner.response) { "Request owner is no longer pending." }
        require(response.requestId == owner.requestId) { "Response request id differs from its owner." }
        completed[owner.requestId] = response
        while (completed.size > capacity) completed.remove(completed.keys.first())
        pending.remove(owner.requestId)
        check(owner.response.complete(response)) { "Request response was already completed." }
    }
}
