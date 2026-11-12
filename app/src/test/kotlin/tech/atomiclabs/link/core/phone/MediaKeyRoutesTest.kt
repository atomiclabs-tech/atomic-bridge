package tech.atomiclabs.link.core.phone

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import tech.atomiclabs.link.core.command.*
import tech.atomiclabs.link.core.fakes.*
import tech.atomiclabs.link.core.port.*
import tech.atomiclabs.link.core.protocol.BridgeCommand

class MediaKeyRoutesTest {
    @Test fun exactFiveActionsHaveNoDisplayGateAndReplayDoesNotDispatchTwice() = runTest {
        val sent = mutableListOf<MediaAction>()
        val group = MediaKeyRoutes(MediaKeys { sent += it; PortResult.Success(42) }, FakeLogger())
        val gate = FakeDisplayGate()
        val router = CommandRouter(listOf(group), FakeUiTree(), gate, FakeLogger())
        for ((index, name) in listOf("play", "pause", "toggle", "next", "previous").withIndex()) {
            val command = BridgeCommand(name, "POST", "/media", body = buildJsonObject { put("action", name) })
            val result = router.dispatch(command)
            assertEquals(buildJsonObject { put("success", true); put("action", name); put("keycode", 42) }, result.result)
            assertEquals(result, router.dispatch(command))
            assertEquals(MediaAction.entries[index], sent.last())
        }
        assertEquals(5, sent.size); assertEquals(0, gate.calls)
    }

    @Test fun unknownActionsAndFailedDispatchesAreErrorsWithSafeLogs() = runTest {
        val logger = FakeLogger()
        var calls = 0
        val group = MediaKeyRoutes(MediaKeys {
            calls++; PortResult.Failure(PortFailure(FailureKind.PLATFORM, IllegalStateException("private-fixture")))
        }, logger)
        val router = CommandRouter(listOf(group), FakeUiTree(), FakeDisplayGate(), logger)
        fun command(id: String, action: String) = BridgeCommand(id, "POST", "/media",
            body = buildJsonObject { put("action", action) })
        assertEquals("bad_request", router.dispatch(command("bad", "private-unknown")).result["code"]!!.jsonPrimitive.content)
        assertEquals("internal_error", router.dispatch(command("fail", "play")).result["code"]!!.jsonPrimitive.content)
        assertEquals(1, calls)
        assertEquals(2, logger.entries.size)
        assertTrue(logger.entries.all { !it.cause!!.stackTraceToString().contains("private-") })
    }
}
