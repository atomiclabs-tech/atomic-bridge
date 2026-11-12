package tech.atomiclabs.link.core.command

import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import tech.atomiclabs.link.core.fakes.*
import tech.atomiclabs.link.core.port.*
import tech.atomiclabs.link.core.protocol.BridgeCommand

class CoreRoutesTest {
    @Test fun pingAndSetupHaveOnlyTheirApprovedShapesAndNoAccessibilityGate() = runTest {
        val tree = FakeUiTree().apply { connection = PortResult.Success(false) }
        val clock = FakeClock(testScheduler, 1234)
        var opened = 0
        val routes = CoreRoutes(tree, clock, { opened++; PortResult.Success(Unit) }, FakeLogger())
        assertEquals(setOf("GET" to "/ping", "POST" to "/setup"), routes.routes.map { it.method to it.path }.toSet())
        val router = CommandRouter(listOf(routes), tree, FakeDisplayGate(), FakeLogger())
        val ping = router.dispatch(BridgeCommand("p", "GET", "/ping")).result
        assertEquals(buildJsonObject {
            put("pong", true); put("ts", 1234); put("accessibilityService", false); put("success", true)
        }, ping)
        val setup = router.dispatch(BridgeCommand("s", "POST", "/setup")).result
        assertEquals(buildJsonObject { put("ok", true); put("success", true) }, setup)
        assertEquals(1, opened)
    }

    @Test fun dependenciesFailLoudlyAndSetupTimeoutCannotReturnSuccess() = runTest {
        val logger = FakeLogger()
        val tree = FakeUiTree().apply { connection = PortResult.Failure(PortFailure(FailureKind.PLATFORM,
            SecurityException("private-fixture"))) }
        val routes = CoreRoutes(tree, FakeClock(testScheduler),
            { kotlinx.coroutines.awaitCancellation() }, logger)
        val router = CommandRouter(listOf(routes), tree, FakeDisplayGate(), logger)
        val ping = router.dispatch(BridgeCommand("p", "GET", "/ping")).result
        assertEquals("internal_error", ping["code"]!!.jsonPrimitive.content)
        val setup = router.dispatch(BridgeCommand("s", "POST", "/setup")).result
        assertEquals("timeout", setup["code"]!!.jsonPrimitive.content)
        assertTrue(logger.entries.any { it.component == "CoreRoutes" && it.level == "error" })
        assertFalse(logger.entries.any { it.cause?.stackTraceToString()?.contains("private-fixture") == true })
    }
}
