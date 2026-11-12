package tech.atomiclabs.link.core.phone

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import tech.atomiclabs.link.core.command.*
import tech.atomiclabs.link.core.fakes.*
import tech.atomiclabs.link.core.port.*
import tech.atomiclabs.link.core.protocol.BridgeCommand

@OptIn(ExperimentalCoroutinesApi::class)
class ClipboardRoutesTest {
    private val logger = FakeLogger()
    private val clipboard = Clipboard()
    private val gate = FakeDisplayGate()
    private val group = ClipboardRoutes(clipboard, logger)
    private val router = CommandRouter(listOf(group), FakeUiTree().apply { connection = PortResult.Success(false) }, gate, logger)
    private suspend fun call(id: String, method: String, body: JsonObject = JsonObject(emptyMap())) =
        router.dispatch(BridgeCommand(id, method, "/clipboard", body = body))

    @Test fun emptyReadsAndWritesSucceedWithExactShapesDisplayAndReplay() = runTest {
        val read = call("r", "GET")
        assertEquals(Json.parseToJsonElement("""{"text":"","success":true}"""), read.result)
        assertEquals(read, call("r", "GET"))
        val write = call("w", "POST", buildJsonObject { put("text", "") })
        assertEquals(Json.parseToJsonElement("""{"length":0,"success":true}"""), write.result)
        assertEquals(write, call("w", "POST", buildJsonObject { put("text", "") }))
        assertEquals(listOf(""), clipboard.writes)
        assertEquals(1, clipboard.reads)
        assertEquals(1, gate.calls)
    }

    @Test fun privateContentIsReturnedOrWrittenButNeverLoggedOnFailure() = runTest {
        clipboard.result = PortResult.Success("private-clipboard")
        assertEquals("private-clipboard", call("r", "GET").result["text"]!!.jsonPrimitive.content)
        clipboard.result = PortResult.Failure(PortFailure(FailureKind.PERMISSION_DENIED, SecurityException("private-clipboard")))
        assertEquals("clipboard_unavailable", call("denied", "GET").result["code"]!!.jsonPrimitive.content)
        clipboard.writeFailure = clipboard.result as PortResult.Failure
        assertEquals("clipboard_unavailable", call("w", "POST", buildJsonObject { put("text", "private-clipboard") }).result["code"]!!.jsonPrimitive.content)
        assertEquals(2, logger.entries.size)
        assertTrue(logger.entries.all { it.level == "error" && !it.cause!!.stackTraceToString().contains("private-clipboard") })
    }

    @Test fun missingTextIsBadRequestAndHungReadIsClipboardUnavailable() = runTest {
        assertEquals("bad_request", call("bad", "POST").result["code"]!!.jsonPrimitive.content)
        clipboard.hang = true
        assertEquals("clipboard_unavailable", call("timeout", "GET").result["code"]!!.jsonPrimitive.content)
        assertEquals(5_000, testScheduler.currentTime)
        assertTrue(clipboard.writes.isEmpty())
        assertEquals(1, clipboard.reads)
    }

    private class Clipboard : ClipboardAccess {
        var result: PortResult<String> = PortResult.Success("")
        var writeFailure: PortResult.Failure? = null
        var reads = 0
        var hang = false
        val writes = mutableListOf<String>()
        override suspend fun read(): PortResult<String> { reads++; if (hang) awaitCancellation(); return result }
        override suspend fun write(text: String): PortResult<Unit> { writes += text; return writeFailure ?: PortResult.Success(Unit) }
    }
}
