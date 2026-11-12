package tech.atomiclabs.link.core.connection

import kotlinx.coroutines.*
import tech.atomiclabs.link.core.command.FailureDiagnostics
import tech.atomiclabs.link.core.log.Logger
import tech.atomiclabs.link.core.port.*
import tech.atomiclabs.link.core.protocol.Envelope

/** Isolates stream entry points while binding every send to the connection that started them. */
internal class OutboundStreams(private val logger: Logger) {
    private var jobs: List<Job> = emptyList()

    fun start(scope: CoroutineScope, streams: List<OutboundStream>, generation: Long,
        isCurrent: () -> Boolean, send: suspend (Envelope) -> Unit) {
        stop()
        jobs = streams.mapIndexed { index, stream ->
            scope.launch {
                val context = "generation=$generation stream=$index"
                try { stream.run { envelope -> boundSend(context, isCurrent, send, envelope) } }
                catch (cause: Throwable) {
                    if (cause is CancellationException && !currentCoroutineContext().isActive) throw cause
                    logger.error("OutboundStreams", "run", context, FailureDiagnostics.sanitized(cause))
                }
            }
        }
    }

    fun stop() { jobs.forEach { it.cancel() }; jobs = emptyList() }

    private suspend fun boundSend(context: String, isCurrent: () -> Boolean,
        send: suspend (Envelope) -> Unit, envelope: Envelope): PortResult<Unit> = try {
        check(isCurrent()) { "The outbound connection ended." }
        send(envelope)
        PortResult.Success(Unit)
    } catch (cause: Exception) {
        if (cause is CancellationException && !currentCoroutineContext().isActive) throw cause
        val failure = PortFailure(FailureKind.IO, FailureDiagnostics.sanitized(cause))
        logger.error("OutboundStreams", "send", context, failure.cause)
        PortResult.Failure(failure)
    }
}
