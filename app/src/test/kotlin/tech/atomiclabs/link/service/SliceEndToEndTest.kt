package tech.atomiclabs.link.service

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import tech.atomiclabs.link.core.fakes.*
import tech.atomiclabs.link.core.port.*
import tech.atomiclabs.link.core.protocol.*
import tech.atomiclabs.link.net.*

class SliceEndToEndTest {
    @Test fun captureIntervalsLeaveReconnectBlockedUntilManualAdvance() = runBlocking {
        val clock = SliceClock()
        val cancelledRetry = launch(start = CoroutineStart.UNDISPATCHED) { clock.sleep(750) }
        cancelledRetry.cancelAndJoin()
        val retry = async(start = CoroutineStart.UNDISPATCHED) { clock.sleep(750) }
        repeat(2) { clock.sleep(400) }
        assertEquals(800L, clock.monotonicMs())
        assertEquals(1_700_000_000_800L, clock.epochMs())
        assertFalse(retry.isCompleted)
        clock.advanceRetry()
        retry.await()
        assertEquals(1_800L, clock.monotonicMs())
    }

    @Test fun actualRuntimePairsReportsAndAnswersItsRoutesAgainAfterServerClose() = runBlocking {
        val logger = FakeLogger()
        val clock = SliceClock()
        val client = createRelayClient()
        val server = MockWebServer()
        val codec = EnvelopeCodec(logger)
        val opened = Channel<WebSocket>(Channel.UNLIMITED)
        val received = Channel<Envelope>(Channel.UNLIMITED)
        val closed = Channel<PortResult<Unit>>(Channel.UNLIMITED)
        val closing = ConcurrentHashMap.newKeySet<WebSocket>()
        val listener = object : WebSocketListener() {
            override fun onOpen(socket: WebSocket, response: Response) { check(opened.trySend(socket).isSuccess) }
            override fun onMessage(socket: WebSocket, text: String) {
                val frame = checkNotNull(codec.decode(text))
                check(received.trySend(frame).isSuccess)
                if (frame.type == "auth") check(socket.send(codec.encode(Envelope("system", "auth.ok", "relay", buildJsonObject {
                    put("session_token", credential)
                    put("grants", buildJsonObject { put("bridge", JsonNull) })
                }))))
            }
            override fun onClosing(socket: WebSocket, code: Int, reason: String) {
                if (closing.add(socket)) check(socket.close(code, reason))
            }
            override fun onClosed(socket: WebSocket, code: Int, reason: String) {
                check(closed.trySend(PortResult.Success(Unit)).isSuccess)
            }
            override fun onFailure(socket: WebSocket, t: Throwable, response: Response?) {
                check(closed.trySend(PortResult.Failure(PortFailure(FailureKind.IO, t))).isSuccess)
            }
        }
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/ws" -> MockResponse().withWebSocketUpgrade(listener)
                "/media/upload" -> MockResponse().setHeader("Content-Type", "application/json")
                    .setBody("""{"ok":true,"token":"fixture-media"}""")
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()
        val settings = FakeSettingsStore().apply {
            url = ReadValue.Present(server.url("/").toString())
            id = ReadValue.Present("11111111-2222-3333-4444-555555555555")
        }
        val node = FakeUiNode(UiNodeProperties(Bounds(0, 0, 2, 2), 9,
            packageName = "fixture.app", text = "Fixture", clickable = true))
        val tree = FakeUiTree().apply { windowResult = PortResult.Success(listOf(
            UiWindow(9, WindowType.APPLICATION, 1, 1, true, true, node, Bounds(0, 0, 2, 2)))) }
        val display = FakeDisplayGate()
        val sessions = FakeSessionStore()
        val inputs = runtimeInputs(clock).copy(
            settings = settings, sessions = sessions, tree = tree, display = display,
            sockets = OkHttpRelaySocketFactory(client, logger),
            media = { RelayMediaClient(client, it, logger) },
            device = { windows -> DeviceStateSource {
                PortResult.Success(DeviceSnapshot(80, true, windows.foregroundPackage(), "Fixture"))
            } },
            close = { client.dispatcher.cancelAll(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() },
        )
        val runtime = LinkRuntime(logger, inputs, CoroutineScope(SupervisorJob() + Dispatchers.Default),
            installFailureHandler = {})
        try {
            repeat(4) { runtime.start() }
            withTimeout(5_000) { runtime.supervisor.states.first { it.phase == ConnectionPhase.UNPAIRED } }
            assertEquals(ConnectionPhase.UNPAIRED, runtime.supervisor.states.value.phase)
            assertEquals(0, server.requestCount)
            val code = (0 until 6).map { (65 + it).toChar() }.joinToString("")
            runtime.connection.pair(code)
            repeat(2) { attempt ->
                withTimeout(8_000) {
                    val socket = opened.receive()
                    val auth = received.receive()
                    assertEquals("auth", auth.type)
                    if (attempt == 0) assertTrue(auth.payload["pairing_code"]!!.jsonPrimitive.content == code)
                    else assertEquals(credential, auth.payload["session_token"]!!.jsonPrimitive.content)
                    val status = received.receive()
                    assertEquals("bridge.status", status.type)
                    assertEquals("fixture.app", status.payload["device"]!!.jsonObject["current_app"]!!.jsonPrimitive.content)
                    runtime.supervisor.states.first { it.phase == ConnectionPhase.ONLINE }
                    for ((index, path) in listOf("/ping", "/screen", "/screenshot").withIndex()) {
                        val id = "command-$attempt-$index"
                        val envelope = codec.commandEnvelope(BridgeCommand(id, "GET", path))
                        check(socket.send(codec.encode(envelope)))
                        val response = received.receive()
                        assertEquals("bridge.response", response.type)
                        assertEquals(id, response.payload["request_id"]!!.jsonPrimitive.content)
                        assertEquals(200, response.payload["status"]!!.jsonPrimitive.int)
                        val result = response.payload["result"]!!.jsonObject
                        assertTrue(result["success"]!!.jsonPrimitive.boolean)
                        when (path) {
                            "/ping" -> {
                                assertEquals(setOf("success", "pong", "ts", "accessibilityService"), result.keys)
                                assertTrue(result["pong"]!!.jsonPrimitive.boolean)
                                assertTrue(result["accessibilityService"]!!.jsonPrimitive.boolean)
                                assertEquals(clock.epochMs(), result["ts"]!!.jsonPrimitive.long)
                            }
                            "/screen" -> {
                                assertEquals(setOf("success", "packageName", "rootBounds", "nodes", "nodeCount", "truncated", "windows"), result.keys)
                                assertEquals("fixture.app", result["packageName"]!!.jsonPrimitive.content)
                                assertEquals("w9:0", result["nodes"]!!.jsonArray.single().jsonObject["nodeId"]!!.jsonPrimitive.content)
                            }
                            "/screenshot" -> {
                                assertEquals(setOf("success", "media", "width", "height", "bytes", "format"), result.keys)
                                assertEquals("MEDIA:hermes-relay://fixture-media", result["media"]!!.jsonPrimitive.content)
                                assertEquals(2, result["width"]!!.jsonPrimitive.int)
                                assertEquals(2, result["height"]!!.jsonPrimitive.int)
                                assertEquals(3, result["bytes"]!!.jsonPrimitive.int)
                                assertEquals("png", result["format"]!!.jsonPrimitive.content)
                            }
                        }
                        if (path == "/screenshot") {
                            val before = display.calls
                            check(socket.send(codec.encode(envelope)))
                            assertEquals(result, received.receive().payload["result"]!!.jsonObject)
                            assertEquals(before, display.calls)
                        }
                    }
                    closing += socket
                    check(socket.close(1000, "Done."))
                    assertTrue(closed.receive() is PortResult.Success)
                    runtime.supervisor.states.first { it.phase == ConnectionPhase.BACKOFF }
                    if (attempt == 0) clock.advanceRetry()
                }
            }
            assertEquals(4, server.requestCount) // Two upgrades and two uploads; duplicate commands are replayed locally.
            assertEquals(2, sessions.saveAttempts.size)
            repeat(4) {
                val request = checkNotNull(server.takeRequest(1, TimeUnit.SECONDS))
                assertFalse(request.requestLine.contains(credential) || request.requestLine.contains(code))
                if (request.path == "/media/upload") {
                    assertEquals("Bearer $credential", request.getHeader("Authorization"))
                    assertTrue(request.body.readUtf8().contains("name=\"file\""))
                }
            }
            assertFalse(logger.entries.any { entry ->
                val text = entry.context + entry.cause?.stackTraceToString()
                text.contains(credential) || text.contains(code)
            })
        } finally { runtime.shutdown(); server.shutdown() }
    }

    private companion object { const val credential = "slice-adopted-fixture" }
}

private class SliceClock : Clock {
    private val time = MutableStateFlow(0L)
    private val retries = Channel<CompletableDeferred<Unit>>(Channel.UNLIMITED)
    override fun monotonicMs() = time.value
    override fun epochMs() = 1_700_000_000_000 + time.value
    override suspend fun sleep(durationMs: Long) {
        currentCoroutineContext().ensureActive()
        when {
            durationMs == 400L -> {
                time.update { it + durationMs }
                yield()
            }
            durationMs in 500L..1_000L -> {
                val release = CompletableDeferred<Unit>()
                check(retries.trySend(release).isSuccess)
                try { release.await() } finally { release.cancel() }
            }
            else -> {
                val until = time.value + durationMs
                time.first { it >= until }
            }
        }
    }
    suspend fun advanceRetry() {
        var release = retries.receive()
        while (!release.isActive) release = retries.receive()
        time.update { it + 1_000 }
        check(release.complete(Unit))
    }
}
