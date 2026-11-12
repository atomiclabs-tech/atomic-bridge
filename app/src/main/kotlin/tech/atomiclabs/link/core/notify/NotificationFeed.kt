package tech.atomiclabs.link.core.notify

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import tech.atomiclabs.link.core.command.FailureDiagnostics
import tech.atomiclabs.link.core.log.Logger
import tech.atomiclabs.link.core.port.*
import tech.atomiclabs.link.core.protocol.Envelope

/** Runtime starts collection once, independently of the authenticated run/cancel lifecycle. */
class NotificationFeed(
    private val source: NotificationSource,
    private val logger: Logger,
    private val frames: NotificationFrames = NotificationFrames(),
) : OutboundStream {
    private data class Pending(val frame: Envelope, val retried: Boolean = false)

    private val queue = ArrayDeque<Pending>()
    private val queueLock = Any()
    private val ready = Channel<Unit>(Channel.CONFLATED)
    private val lifecycle = Mutex()
    private val running = Mutex()
    private var subscription: NotificationSubscription? = null

    /** Registers even while disconnected, so the bounded queue includes offline events. */
    suspend fun start(): PortResult<Unit> = lifecycle.withLock {
        if (subscription != null) return@withLock PortResult.Success(Unit)
        val result = try {
            withTimeout(5_000) { source.register { enqueue(Pending(frames.frame(it))) } }
        } catch (cause: TimeoutCancellationException) {
            PortResult.Failure(PortFailure(FailureKind.TIMED_OUT, cause))
        }
        when (result) {
            is PortResult.Success -> {
                subscription = result.value
                PortResult.Success(Unit)
            }
            is PortResult.Failure -> logFailure("register", result)
        }
    }

    /** Runtime cancels its stream before closing collection at process teardown. */
    suspend fun close(): PortResult<Unit> = lifecycle.withLock {
        val current = subscription ?: return@withLock PortResult.Success(Unit)
        when (val result = current.close()) {
            is PortResult.Success -> {
                subscription = null
                result
            }
            is PortResult.Failure -> logFailure("close", result)
        }
    }

    override suspend fun run(send: suspend (Envelope) -> PortResult<Unit>) = running.withLock {
        lifecycle.withLock { check(subscription != null) { "Notification collection has not started." } }
        while (true) {
            currentCoroutineContext().ensureActive()
            val pending = synchronized(queueLock) { queue.removeFirstOrNull() }
            if (pending == null) {
                ready.receive()
                continue
            }
            val result = try {
                withTimeout(10_000) { send(pending.frame) }
            } catch (cause: TimeoutCancellationException) {
                logFailure("send", PortResult.Failure(PortFailure(FailureKind.TIMED_OUT, cause)))
            } catch (cause: CancellationException) {
                retry(pending, cause)
                throw cause
            }
            if (result is PortResult.Failure) retry(pending, result.failure.cause)
        }
    }

    private fun enqueue(pending: Pending) {
        synchronized(queueLock) {
            if (queue.size == 200) {
                queue.removeFirst()
                logger.warn("NotificationFeed", "enqueue", "state=buffer_full oldest_dropped=true")
            }
            queue.addLast(pending)
            check(ready.trySend(Unit).isSuccess) { "The notification wake signal was refused." }
        }
    }

    private fun retry(pending: Pending, cause: Throwable) {
        val state = if (pending.retried) "dropped" else "retry_queued"
        logger.warn("NotificationFeed", "send", "state=$state", FailureDiagnostics.sanitized(cause))
        if (!pending.retried) enqueue(pending.copy(retried = true))
    }

    private fun logFailure(operation: String, result: PortResult.Failure): PortResult.Failure {
        logger.error("NotificationFeed", operation, "state=failed", FailureDiagnostics.sanitized(result.failure.cause))
        return result
    }
}
