package tech.atomiclabs.link.net

import io.mockk.*
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import tech.atomiclabs.link.core.command.*
import tech.atomiclabs.link.core.command.Route
import tech.atomiclabs.link.core.connection.*
import tech.atomiclabs.link.core.fakes.*
import tech.atomiclabs.link.core.port.*
import tech.atomiclabs.link.core.protocol.*
import tech.atomiclabs.link.core.status.StatusReporter

@OptIn(ExperimentalCoroutinesApi::class)
class OkHttpRelaySocketTest {
    @Test fun sharedClientHasProductionBoundsSystemTrustAndNoAutomaticReplay() {
        val client = createRelayClient()
        try {
            assertEquals(10_000, client.connectTimeoutMillis)
            assertEquals(20_000, client.pingIntervalMillis)
            assertEquals(0, client.readTimeoutMillis)
            assertEquals(0, client.callTimeoutMillis)
            assertEquals(5_000, client.webSocketCloseTimeout)
            assertFalse(client.retryOnConnectionFailure)
            assertFalse(client.followRedirects)
            assertFalse(client.followSslRedirects)
            assertEquals(1, client.interceptors.size)
            assertTrue(client.networkInterceptors.isEmpty())
            assertTrue(client.certificatePinner.pins.isEmpty())
        } finally { shutdown(client) }
    }

    @Test fun realSupervisorPairsReportsAnswersACommandAndReconnectsWithTheAdoptedSession() = runBlocking {
        val server = MockWebServer()
        val client = createRelayClient()
        val codec = EnvelopeCodec(FakeLogger())
        val opened = Channel<WebSocket>(Channel.UNLIMITED)
        val closingSockets = ConcurrentHashMap.newKeySet<WebSocket>()
        val closed = Channel<PortResult<Unit>>(Channel.UNLIMITED)
        val received = Channel<Envelope>(Channel.UNLIMITED)
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                check(opened.trySend(webSocket).isSuccess)
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                if (closingSockets.add(webSocket)) check(webSocket.close(code, reason))
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                check(closed.trySend(PortResult.Success(Unit)).isSuccess)
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                check(closed.trySend(PortResult.Failure(PortFailure(FailureKind.IO, t))).isSuccess)
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                val frame = checkNotNull(codec.decode(text))
                check(received.trySend(frame).isSuccess)
                if (frame.type == "auth") check(webSocket.send(codec.encode(authOk())))
            }
        }
        server.enqueue(MockResponse().withWebSocketUpgrade(listener))
        server.enqueue(MockResponse().withWebSocketUpgrade(listener))
        server.start()
        val h = LoopbackConnection(client, server.url("/").toString(), ReadValue.Absent)
        try {
            withTimeout(5_000) { h.supervisor.states.first { it.phase == ConnectionPhase.UNPAIRED } }
            val code = (0 until 6).map { (65 + it).toChar() }.joinToString("")
            assertEquals(0, server.requestCount)
            h.supervisor.pair(code)
            withTimeout(5_000) {
                val firstSocket = opened.receive()
                val first = received.receive()
                assertEquals("auth", first.type)
                assertTrue(first.payload["pairing_code"]!!.jsonPrimitive.content == code)
                assertEquals("bridge.status", received.receive().type)
                check(firstSocket.send(codec.encode(codec.commandEnvelope(
                    BridgeCommand("00000000-0000-0000-0000-000000000099", "POST", "/fixture")))))
                val response = received.receive()
                assertEquals("bridge.response", response.type)
                assertEquals(200, response.payload["status"]!!.jsonPrimitive.int)
                assertEquals(1, h.commands.get())
                assertTrue((h.supervisor.token() as ReadValue.Present).value == sessionFixture)
                closingSockets += firstSocket
                check(firstSocket.close(1000, "Done."))
                assertTrue(closed.receive() is PortResult.Success)
                h.supervisor.states.first { it.phase == ConnectionPhase.BACKOFF }
                h.clock.releaseAfter(500)
                val secondSocket = opened.receive()
                val second = received.receive()
                assertEquals("auth", second.type)
                assertFalse(second.payload.containsKey("pairing_code"))
                assertTrue(second.payload["session_token"]!!.jsonPrimitive.content == sessionFixture)
                assertEquals("bridge.status", received.receive().type)
                closingSockets += secondSocket
                check(secondSocket.close(1000, "Done."))
                assertTrue(closed.receive() is PortResult.Success)
            }
            repeat(2) {
                val request = checkNotNull(server.takeRequest(2, java.util.concurrent.TimeUnit.SECONDS))
                assertFalse(request.requestLine.contains(code) || request.requestLine.contains(sessionFixture))
                assertEquals("/ws", request.path)
            }
            assertPrivate(h.logger, code, sessionFixture)
        } finally { h.stop(); shutdown(client); server.shutdown() }
    }

    @Test fun upgrade429ReachesTheRealSupervisorWithoutAnImmediateRetry() = runBlocking {
        val server = MockWebServer()
        val client = createRelayClient()
        server.enqueue(MockResponse().setResponseCode(429).setBody("private-upgrade-body"))
        server.start()
        val h = LoopbackConnection(client, server.url("/").toString())
        try {
            val state = withTimeout(5_000) { h.supervisor.states.first { it.phase == ConnectionPhase.BACKOFF } }
            assertEquals(429, state.failure!!.httpStatus)
            assertEquals(FailureKind.REJECTED, state.failure.kind)
            h.clock.awaitSleep(300_000)
            assertEquals(1, server.requestCount)
            assertPrivate(h.logger, "private-upgrade-body")
        } finally { h.stop(); shutdown(client); server.shutdown() }
    }

    @Test fun refusedConnectionReachesTheRealSupervisorAsTransportFailure() = runBlocking {
        val port = ServerSocket(0).use { it.localPort }
        val client = createRelayClient()
        val h = LoopbackConnection(client, "http://127.0.0.1:$port")
        try {
            val state = withTimeout(5_000) { h.supervisor.states.first { it.phase == ConnectionPhase.BACKOFF } }
            assertEquals(FailureKind.IO, state.failure!!.kind)
            assertNull(state.failure.httpStatus)
            assertTrue(state.failure.cause.stackTrace.isNotEmpty())
            assertTrue(h.logger.entries.any { it.operation == "connect" && it.level == "error" })
            h.clock.awaitSleep(500)
        } finally { h.stop(); shutdown(client) }
    }

    @Test fun upgrade503WithImmediateRetryHeaderIsReportedOnceWithoutAnAutomaticSecondUpgrade() = runBlocking {
        val server = MockWebServer()
        val client = createRelayClient()
        server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "0"))
        server.enqueue(MockResponse().setResponseCode(200))
        server.start()
        val h = LoopbackConnection(client, server.url("/").toString())
        try {
            val state = withTimeout(5_000) { h.supervisor.states.first { it.phase == ConnectionPhase.BACKOFF } }
            assertEquals(503, state.failure!!.httpStatus)
            assertEquals(FailureKind.REJECTED, state.failure.kind)
            h.clock.awaitSleep(500)
            assertEquals(1, server.requestCount)
        } finally { h.stop(); shutdown(client); server.shutdown() }
    }

    @Test fun stalledUpgradeTimesOutAndCancelsTheUnderlyingSocket() = runTest {
        val server = MockWebServer()
        val client = createRelayClient()
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        server.start()
        val logger = FakeLogger()
        val opening = async { OkHttpRelaySocketFactory(client, logger).connect(server.url("/ws").toString()) }
        try {
            runCurrent()
            assertNotNull("The port-0 server must observe the stalled upgrade.",
                server.takeRequest(2, java.util.concurrent.TimeUnit.SECONDS))
            advanceTimeBy(9_999); runCurrent()
            assertFalse(opening.isCompleted)
            advanceTimeBy(1); runCurrent()
            assertTrue("The default socket open must finish at 10 seconds.", opening.isCompleted)
            val result = opening.await() as PortResult.Failure
            assertEquals(10_000L, testScheduler.currentTime)
            assertEquals(FailureKind.TIMED_OUT, result.failure.kind)
            assertEquals(1, server.requestCount)
            val error = logger.entries.single { it.level == "error" }
            assertEquals("connect", error.operation)
            assertSame(result.failure.cause, error.cause)
            assertTrue(error.cause!!.stackTrace.isNotEmpty())
        } finally { opening.cancelAndJoin(); shutdown(client); server.shutdown() }
    }

    @Test fun refusedSendAndCloseAreCheckedOnceAndFailureCallbacksCloseResponsesWithoutPrivateDiagnostics() = runBlocking {
        val factory = mockk<WebSocket.Factory>()
        val native = mockk<WebSocket>(relaxed = true)
        val listener = slot<WebSocketListener>()
        every { factory.newWebSocket(any(), capture(listener)) } returns native
        every { native.send(any<String>()) } returns false
        every { native.close(any(), any()) } returns false
        val logger = FakeLogger()
        val socket = OkHttpRelaySocket(factory, "ws://localhost/ws", logger)
        listener.captured.onOpen(native, mockk())
        assertEquals(FailureKind.REJECTED, (socket.send("private-frame") as PortResult.Failure).failure.kind)
        assertEquals(FailureKind.REJECTED, (socket.close(1000, "private-close") as PortResult.Failure).failure.kind)
        verify(exactly = 1) { native.send("private-frame") }
        verify(exactly = 1) { native.close(1000, "private-close") }
        val response = mockk<Response>(relaxed = true)
        every { response.code } returns 429
        val cause = IllegalStateException("private-transport", SecurityException("private-cause"))
        listener.captured.onFailure(native, cause, response)
        val events = socket.events.toList()
        assertSame(SocketEvent.Opened, events.first())
        val failed = (events.last() as SocketEvent.Failed).failure
        assertEquals(429, failed.httpStatus)
        assertArrayEquals(cause.stackTrace, failed.cause.stackTrace)
        assertNotNull(failed.cause.cause)
        verify(exactly = 1) { response.close() }
        assertPrivate(logger, "private-frame", "private-close", "private-transport", "private-cause")
    }

    @Test fun closeAcknowledgementDoesNotRepeatTheCloseOrCancelAGracefulCompletion() = runBlocking {
        val factory = mockk<WebSocket.Factory>()
        val native = mockk<WebSocket>(relaxed = true)
        val listener = slot<WebSocketListener>()
        every { factory.newWebSocket(any(), capture(listener)) } returns native
        every { native.close(1000, "Done.") } returns true
        val logger = FakeLogger()
        val socket = OkHttpRelaySocket(factory, "ws://localhost/ws", logger)
        listener.captured.onOpen(native, mockk())
        assertTrue(socket.close(1000, "Done.") is PortResult.Success)
        listener.captured.onClosing(native, 1000, "Done.")
        listener.captured.onClosed(native, 1000, "Done.")
        assertEquals(2, socket.events.take(2).toList().size)
        verify(exactly = 1) { native.close(1000, "Done.") }
        verify(exactly = 0) { native.cancel() }
        assertTrue(logger.entries.isEmpty())
    }

    private fun authOk() = Envelope("system", "auth.ok", "relay", buildJsonObject {
        put("session_token", sessionFixture)
        put("grants", buildJsonObject { put("bridge", JsonNull) })
    })
}

private const val sessionFixture = "adopted-session-fixture"

internal fun shutdown(client: OkHttpClient) {
    client.dispatcher.cancelAll()
    client.connectionPool.evictAll()
    client.dispatcher.executorService.shutdown()
}

internal fun assertPrivate(logger: FakeLogger, vararg values: String) {
    logger.entries.forEach { entry ->
        val logged = "${entry.context} ${entry.cause?.stackTraceToString()}"
        values.forEach { assertFalse("A private fixture entered diagnostics.", logged.contains(it)) }
    }
}

private class LoopbackClock : Clock {
    private val time = MutableStateFlow(0L)
    private val sleeps = Channel<Long>(Channel.UNLIMITED)
    override fun monotonicMs() = time.value
    override fun epochMs() = time.value + 1_700_000_000_000
    override suspend fun sleep(durationMs: Long) {
        val until = time.value + durationMs
        check(sleeps.trySend(durationMs).isSuccess)
        time.first { it >= until }
    }
    suspend fun awaitSleep(durationMs: Long) { withTimeout(5_000) { while (sleeps.receive() != durationMs) Unit } }
    suspend fun releaseAfter(durationMs: Long) { awaitSleep(durationMs); time.value += durationMs }
}

private class LoopbackConnection(client: OkHttpClient, url: String, initial: ReadValue<StoredSession> = ReadValue.Present(originalSession())) {
    val logger = FakeLogger()
    val clock = LoopbackClock()
    val commands = AtomicInteger()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val ids = AtomicInteger()
    private fun newId() = "00000000-0000-0000-0000-${ids.incrementAndGet().toString().padStart(12, '0')}"
    private val identity = DeviceIdentity { PortResult.Success(description()) }
    private val store = FakeSessionStore().apply { value = initial }
    private val settings = FakeSettingsStore().apply {
        this.url = ReadValue.Present(url)
        id = ReadValue.Present("11111111-2222-3333-4444-555555555555")
    }
    private val network = object : NetworkMonitor {
        override val changes = emptyFlow<PortResult<Boolean>>()
        override suspend fun isAvailable() = PortResult.Success(true)
    }
    private val router = CommandRouter(listOf(object : RouteGroup {
        override val routes = listOf(Route("POST", "/fixture") { commands.incrementAndGet(); CommandResult.Success() })
    }), FakeUiTree(), FakeDisplayGate(), logger)
    private val reporter = StatusReporter(identity, DeviceStateSource { PortResult.Success(DeviceSnapshot(50, true, null, null)) },
        PermissionState { PortResult.Success(permissions()) }, clock, logger, ::newId)
    val supervisor = ConnectionSupervisor(OkHttpRelaySocketFactory(client, logger), network, store, settings, identity, clock,
        router, reporter, logger, ::newId, ReconnectPolicy { 0 })
    private val job = scope.launch { supervisor.run() }
    suspend fun stop() { job.cancelAndJoin(); scope.cancel() }
}
