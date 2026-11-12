package tech.atomiclabs.link.core.command

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import tech.atomiclabs.link.core.fakes.*
import tech.atomiclabs.link.core.port.*
import tech.atomiclabs.link.core.protocol.*

@OptIn(ExperimentalCoroutinesApi::class)
class CommandRouterDeadlinesTest {
    private val tree = FakeUiTree()
    private val gate = FakeDisplayGate()
    private val logger = FakeLogger()
    private val requestId = "11111111-2222-3333-4444-555555555555"
    private val ok = CommandResult.Success()
    private fun router(registered: List<Route>, overrides: Map<Pair<String, String>, CommandDeadline>,
        fallback: CommandDeadline = CommandDeadline()) = CommandRouter(
        listOf(object : RouteGroup { override val routes = registered }), tree, gate, logger,
        deadline = fallback, routeDeadlines = overrides,
    )
    private fun command(id: String = requestId, method: String = "POST", path: String = "/slow",
        body: JsonObject = JsonObject(emptyMap())) = BridgeCommand(id, method, path, body = body)
    private fun failure(response: BridgeResponse, code: ErrorCode) {
        assertEquals(200, response.status)
        assertEquals(JsonPrimitive(false), response.result["success"])
        assertEquals(JsonPrimitive(code.wireValue), response.result["code"])
    }

    @Test fun explicitRouteCompletesBeyond25SecondsWithCopiedMapAndPendingAndCompletedReplay() = runTest {
        var calls = 0
        val configured = mutableMapOf(("POST" to "/slow") to CommandDeadline(35_000))
        val router = router(listOf(Route("POST", "/slow") { calls++; delay(30_000); ok }), configured)
        configured.clear()
        configured["POST" to "/other"] = CommandDeadline(1)
        val misleadingBody = buildJsonObject { put("timeout_ms", 1); put("method", "GET"); put("path", "/other") }
        val first = async { router.dispatch(command(body = misleadingBody)) }
        runCurrent()
        val duplicate = async { router.dispatch(command(path = "/other")) }
        runCurrent()
        advanceTimeBy(25_000); runCurrent()
        assertFalse(first.isCompleted); assertFalse(duplicate.isCompleted)
        advanceTimeBy(5_000); runCurrent()
        assertEquals(JsonPrimitive(true), first.await().result["success"])
        assertSame(first.await(), duplicate.await())
        assertSame(first.await(), router.dispatch(command()))
        assertEquals(30_000, currentTime)
        assertEquals(1, calls)
        assertTrue(logger.entries.isEmpty())
    }

    @Test fun explicitTimeoutWinsOverInjectedFallbackCancelsAtItsExactBudgetAndReplays() = runTest {
        var calls = 0
        var cancelled = false
        val router = router(listOf(Route("POST", "/slow") {
            calls++
            try { awaitCancellation() } finally { cancelled = true }
        }), mapOf(("POST" to "/slow") to CommandDeadline(40_000)), CommandDeadline(5_000))
        val first = async { router.dispatch(command()) }
        runCurrent()
        val duplicate = async { router.dispatch(command()) }
        runCurrent()
        advanceTimeBy(39_999); runCurrent()
        assertFalse(first.isCompleted); assertFalse(duplicate.isCompleted)
        advanceTimeBy(1); runCurrent()
        failure(first.await(), ErrorCode.TIMEOUT)
        assertEquals(JsonPrimitive("The command exceeded its deadline."), first.await().result["error"])
        assertTrue(cancelled)
        assertSame(first.await(), duplicate.await())
        assertSame(first.await(), router.dispatch(command()))
        assertEquals(40_000, currentTime); assertEquals(1, calls)
        val diagnostic = logger.entries.single()
        assertEquals("error", diagnostic.level); assertEquals("deadline", diagnostic.operation)
        assertEquals("request_id=$requestId path=/slow", diagnostic.context)
        assertNotNull(diagnostic.cause)
    }

    @Test fun bothMethodAndPathMustMatchOtherwiseTheExistingInjectedDeadlineApplies() = runTest {
        val routes = listOf(Route("GET", "/slow") { awaitCancellation() },
            Route("POST", "/other") { awaitCancellation() })
        val router = router(routes, mapOf(("POST" to "/slow") to CommandDeadline(40_000)), CommandDeadline(7_000))
        val first = async { router.dispatch(command(method = "GET")) }
        val otherPath = async { router.dispatch(command(id = "other", path = "/other",
            body = buildJsonObject { put("timeout_ms", 999_999) })) }
        runCurrent(); advanceTimeBy(6_999); runCurrent()
        assertFalse(first.isCompleted); assertFalse(otherPath.isCompleted)
        advanceTimeBy(1); runCurrent()
        failure(first.await(), ErrorCode.TIMEOUT); failure(otherPath.await(), ErrorCode.TIMEOUT)
        assertEquals(7_000, currentTime)
    }

    @Test fun explicitBudgetStillIncludesDisplayQueueAndPreparation() = runTest {
        var calls = 0
        gate.beforePrepare = { delay(40_000) }
        val router = router(listOf(Route("POST", "/slow", { RouteNeeds(true, true) }) { calls++; ok }),
            mapOf(("POST" to "/slow") to CommandDeadline(30_000)))
        val first = async { router.dispatch(command()) }
        val second = async { router.dispatch(command(id = "second")) }
        runCurrent(); advanceTimeBy(25_000); runCurrent()
        assertFalse(first.isCompleted); assertFalse(second.isCompleted)
        advanceTimeBy(5_000); runCurrent()
        failure(first.await(), ErrorCode.TIMEOUT); failure(second.await(), ErrorCode.TIMEOUT)
        assertEquals(30_000, currentTime)
        assertEquals(0, calls); assertEquals(1, gate.calls)
    }

    @Test fun overridesDoNotChangeUnknownCommandOrAccessibilityPreconditionErrors() = runTest {
        var calls = 0
        tree.connection = PortResult.Success(false)
        val router = router(listOf(Route("POST", "/slow", { RouteNeeds(true, true) }) { calls++; ok }),
            mapOf(("POST" to "/slow") to CommandDeadline(40_000), ("POST" to "/missing") to CommandDeadline(1)))
        failure(router.dispatch(command(path = "/missing")), ErrorCode.UNKNOWN_COMMAND)
        assertEquals(0, tree.connectionReads)
        failure(router.dispatch(command(id = "second")), ErrorCode.ACCESSIBILITY_DISABLED)
        assertEquals(1, tree.connectionReads)
        assertEquals(0, gate.calls); assertEquals(0, calls); assertEquals(0, currentTime)
    }
}
