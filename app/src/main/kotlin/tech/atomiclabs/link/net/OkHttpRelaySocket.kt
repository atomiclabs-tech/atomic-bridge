package tech.atomiclabs.link.net

import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.receiveAsFlow
import okhttp3.*
import okio.ByteString
import tech.atomiclabs.link.core.command.FailureDiagnostics
import tech.atomiclabs.link.core.log.Logger
import tech.atomiclabs.link.core.port.*

/** Runtime creates this client once and shares it between socket attempts and media transfers. */
fun createRelayClient(): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(10, TimeUnit.SECONDS).pingInterval(20, TimeUnit.SECONDS)
    .readTimeout(0, TimeUnit.MILLISECONDS).callTimeout(0, TimeUnit.MILLISECONDS)
    .webSocketCloseTimeout(5, TimeUnit.SECONDS)
    .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
    .addInterceptor { chain ->
        val attempt = checkNotNull(chain.request().tag(RelayAttempt::class.java)) { "A relay attempt is required." }
        chain.call().addEventListener(object : EventListener() {
            override fun followUpDecision(call: Call, networkResponse: Response, nextRequest: Request?) {
                // OkHttp can replay a 503 despite retryOnConnectionFailure(false), including websocket upgrades.
                if (nextRequest != null) {
                    attempt.refusedHttpStatus = networkResponse.code
                    call.cancel()
                }
            }
        })
        chain.proceed(chain.request())
    }
    .build()

internal class RelayAttempt { @Volatile var refusedHttpStatus: Int? = null }
internal fun Request.Builder.singleAttempt(): Request = tag(RelayAttempt::class.java, RelayAttempt()).build()

class OkHttpRelaySocketFactory(
    private val client: OkHttpClient,
    private val logger: Logger,
    private val openTimeoutMs: Long = 10_000,
) : RelaySocketFactory {
    override suspend fun connect(websocketUrl: String): PortResult<RelaySocket> {
        var socket: OkHttpRelaySocket? = null
        return try {
            withTimeout(openTimeoutMs) {
                val created = OkHttpRelaySocket(client, websocketUrl, logger).also { socket = it }
                when (val opened = created.opened.await()) {
                    is PortResult.Success -> PortResult.Success(created)
                    is PortResult.Failure -> opened
                }
            }
        } catch (cause: TimeoutCancellationException) {
            socket?.cancel()
            socketFailure(logger, "connect", cause, FailureKind.TIMED_OUT)
        } catch (cause: CancellationException) {
            socket?.cancel()
            throw cause
        } catch (cause: Exception) {
            socket?.cancel()
            socketFailure(logger, "connect", cause, FailureKind.IO)
        }
    }
}

/** Buffers Opened before returning from the factory; one collector owns the socket's lifetime. */
class OkHttpRelaySocket internal constructor(client: WebSocket.Factory, url: String, private val logger: Logger) : RelaySocket {
    internal val opened = CompletableDeferred<PortResult<Unit>>()
    private val inbound = Channel<SocketEvent>(Channel.UNLIMITED)
    private val terminal = AtomicBoolean()
    private val closing = AtomicBoolean()
    private val request = Request.Builder().url(url).singleAttempt()
    private val socket = client.newWebSocket(request, object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            emit(SocketEvent.Opened)
            opened.complete(PortResult.Success(Unit))
        }

        override fun onMessage(webSocket: WebSocket, text: String) { emit(SocketEvent.Text(text)) }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            val failure = socketFailure(logger, "receive", IOException("Binary relay frames are unsupported."), FailureKind.INVALID_DATA)
            finish(SocketEvent.Failed(failure.failure))
            webSocket.cancel()
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            if (terminal.get()) return
            val alreadyClosing = closing.getAndSet(true)
            if (!alreadyClosing && !webSocket.close(code, reason)) {
                val failure = socketFailure(logger, "close", IOException("The socket refused its close acknowledgement."), FailureKind.REJECTED)
                finish(SocketEvent.Failed(failure.failure))
                webSocket.cancel()
            } else finish(SocketEvent.Closed(code, reason))
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { finish(SocketEvent.Closed(code, reason)) }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            val status = response?.code ?: request.tag(RelayAttempt::class.java)?.refusedHttpStatus
            try { response?.close() }
            catch (cause: Exception) { socketFailure(logger, "closeUpgrade", cause, FailureKind.IO, status) }
            if (terminal.get()) return // Cancellation can deliver a final callback after ownership ended.
            val failure = socketFailure(logger, "receive", t, if (status == null) FailureKind.IO else FailureKind.REJECTED, status)
            opened.complete(failure)
            finish(SocketEvent.Failed(failure.failure))
        }
    })

    override val events = inbound.receiveAsFlow().onCompletion { cause -> if (cause != null && !terminal.get()) cancel() }

    override suspend fun send(frame: String): PortResult<Unit> = checked("send") {
        check(socket.send(frame)) { "The socket refused the frame." }
    }

    override suspend fun close(code: Int, reason: String): PortResult<Unit> {
        if (closing.get() || terminal.get()) return PortResult.Success(Unit)
        return checked("close") {
            check(socket.close(code, reason)) { "The socket refused the close request." }
            closing.set(true)
        }
    }

    internal fun cancel() {
        terminal.set(true)
        closing.set(true)
        socket.cancel()
        inbound.cancel()
    }

    private suspend fun checked(operation: String, action: () -> Unit): PortResult<Unit> = try {
        withTimeout(5_000) { action() }
        PortResult.Success(Unit)
    } catch (cause: TimeoutCancellationException) {
        socketFailure(logger, operation, cause, FailureKind.TIMED_OUT)
    } catch (cause: CancellationException) {
        throw cause
    } catch (cause: Exception) {
        socketFailure(logger, operation, cause, FailureKind.REJECTED)
    }

    private fun emit(event: SocketEvent) {
        if (inbound.trySend(event).isFailure && !terminal.get()) {
            logger.warn("OkHttpRelaySocket", "receive", "state=observer_closed")
        }
    }

    private fun finish(event: SocketEvent) {
        if (terminal.compareAndSet(false, true)) {
            emit(event)
            if (!inbound.close()) logger.warn("OkHttpRelaySocket", "finish", "state=observer_closed")
        }
    }
}

private fun socketFailure(logger: Logger, operation: String, cause: Throwable, kind: FailureKind, status: Int? = null): PortResult.Failure {
    val safe = FailureDiagnostics.sanitized(cause)
    logger.error("OkHttpRelaySocket", operation, "state=failed http_status=$status", safe)
    return PortResult.Failure(PortFailure(kind, safe, httpStatus = status))
}
