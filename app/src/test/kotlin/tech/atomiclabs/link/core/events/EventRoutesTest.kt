package tech.atomiclabs.link.core.events

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import tech.atomiclabs.link.core.command.*
import tech.atomiclabs.link.core.fakes.*
import tech.atomiclabs.link.core.port.PortResult
import tech.atomiclabs.link.core.protocol.BridgeCommand

@OptIn(ExperimentalCoroutinesApi::class)
class EventRoutesTest {
    @Test fun streamCommandsAlwaysClearAndReplayCannotRepeatTheirMutation() = runTest {
        val f = EventsFixture(testScheduler)
        assertEquals(setOf("GET" to "/events", "POST" to "/events/stream"),
            f.group.routes.map { it.method to it.path }.toSet())
        assertTrue(f.group.routes.all { it.needs(BodyParams(JsonObject(emptyMap()))) == RouteNeeds(true, false) })
        assertFalse(f.send()["streaming"]!!.jsonPrimitive.boolean)
        val enabled = f.send("POST", "/events/stream", buildJsonObject { put("enabled", true) }, "toggle")
        assertTrue(enabled["streaming"]!!.jsonPrimitive.boolean)
        assertTrue(enabled["buffer_cleared"]!!.jsonPrimitive.boolean)
        f.append()
        assertEquals(enabled, f.send("POST", "/events/stream", buildJsonObject { put("enabled", false) }, "toggle"))
        assertEquals(1, f.send()["count"]!!.jsonPrimitive.int)
        f.send("POST", "/events/stream", buildJsonObject { put("enabled", true) })
        assertEquals(0, f.send()["count"]!!.jsonPrimitive.int)
        f.append()
        val disabled = f.send("POST", "/events/stream", buildJsonObject { put("enabled", false) })
        assertFalse(disabled["streaming"]!!.jsonPrimitive.boolean)
        assertTrue(disabled["buffer_cleared"]!!.jsonPrimitive.boolean)
        assertEquals(0, f.send()["count"]!!.jsonPrimitive.int)
        assertEquals(0, f.gate.calls)
    }

    @Test fun pollAcceptsNumericAndStringArgumentsAndReturnsAllEntryFields() = runTest {
        val f = EventsFixture(testScheduler)
        f.buffer.setStreaming(true)
        repeat(60) { f.append(); advanceTimeBy(100) }
        assertEquals(50, f.send()["count"]!!.jsonPrimitive.int)
        for (stringForm in listOf(false, true)) {
            val result = f.send(body = buildJsonObject {
                put("limit", if (stringForm) JsonPrimitive("2") else JsonPrimitive(2))
                put("since", if (stringForm) JsonPrimitive("6700") else JsonPrimitive(6700))
            })
            assertEquals(2, result["count"]!!.jsonPrimitive.int)
            assertTrue(result["streaming"]!!.jsonPrimitive.boolean)
            val entries = result["entries"]!!.jsonArray
            assertEquals(listOf(6800L, 6900L), entries.map { it.jsonObject["timestamp"]!!.jsonPrimitive.long })
            val entry = entries.last().jsonObject
            assertEquals(setOf("timestamp", "event_type", "package_name", "class_name", "text", "content_description", "source"), entry.keys)
            assertEquals("click", entry["event_type"]!!.jsonPrimitive.content)
            assertEquals("example", entry["package_name"]!!.jsonPrimitive.content)
            assertEquals("Field", entry["class_name"]!!.jsonPrimitive.content)
            assertEquals("private-text", entry["text"]!!.jsonPrimitive.content)
            assertEquals("description", entry["content_description"]!!.jsonPrimitive.content)
            assertEquals("TYPE_VIEW_CLICKED", entry["source"]!!.jsonPrimitive.content)
        }
        assertEquals(1, f.send(body = buildJsonObject { put("limit", 0) })["count"]!!.jsonPrimitive.int)
        assertEquals(60, f.send(body = buildJsonObject { put("limit", 501) })["count"]!!.jsonPrimitive.int)
        assertEquals(0, f.send(body = buildJsonObject { put("since", "6900") })["count"]!!.jsonPrimitive.int)
        assertFalse(f.logger.entries.any { it.context.contains("private-text") })
    }

    @Test fun strictBooleanAndMalformedQueriesFailAndAccessibilityNeverWakesDisplay() = runTest {
        val f = EventsFixture(testScheduler)
        val invalid = listOf<JsonElement?>(null, JsonNull, JsonPrimitive("true"), JsonPrimitive(1), JsonObject(emptyMap()))
        for (enabled in invalid) {
            val result = f.send("POST", "/events/stream", buildJsonObject { if (enabled != null) put("enabled", enabled) })
            assertEquals("bad_request", result["code"]!!.jsonPrimitive.content)
        }
        for (key in listOf("limit", "since")) {
            assertEquals("bad_request", f.send(body = buildJsonObject { put(key, "invalid") })["code"]!!.jsonPrimitive.content)
        }
        f.tree.connection = PortResult.Success(false)
        assertEquals("accessibility_disabled", f.send()["code"]!!.jsonPrimitive.content)
        assertEquals("accessibility_disabled", f.send("POST", "/events/stream",
            buildJsonObject { put("enabled", true) })["code"]!!.jsonPrimitive.content)
        assertEquals(0, f.gate.calls)
    }
}

private class EventsFixture(scheduler: TestCoroutineScheduler) {
    val logger = FakeLogger()
    val buffer = EventBuffer(FakeClock(scheduler, 1000))
    val tree = FakeUiTree()
    val gate = FakeDisplayGate()
    val group = EventRoutes(buffer)
    private val router = CommandRouter(listOf(group), tree, gate, logger)
    private var nextId = 0
    fun append() = buffer.append(EventBuffer.Type.CLICK, "example", "Field", "private-text", "description")
    suspend fun send(method: String = "GET", path: String = "/events", body: JsonObject = JsonObject(emptyMap()),
        id: String = "events-${++nextId}"): JsonObject {
        val response = router.dispatch(BridgeCommand(id, method, path, body = body))
        assertEquals(200, response.status)
        return response.result
    }
}
