// forge-allow: no-test declarations only
package tech.atomiclabs.link.core.port

import tech.atomiclabs.link.core.protocol.Envelope

/** Runs once per authenticated connection; cancellation ends that run and sends never cross connections. */
fun interface OutboundStream {
    suspend fun run(send: suspend (Envelope) -> PortResult<Unit>)
}
