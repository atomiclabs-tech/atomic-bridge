package tech.atomiclabs.link.core.screen

import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import tech.atomiclabs.link.core.command.*
import tech.atomiclabs.link.core.fakes.*
import tech.atomiclabs.link.core.port.*
import tech.atomiclabs.link.core.protocol.BridgeCommand

class ScreenRoutesTest {
    private val logger = FakeLogger()
    private val tree = FakeUiTree()
    private val gate = FakeDisplayGate()
    private val app = node("app content", id = 17)
    private val system = node("system bar", id = 2).also {
        it.properties = it.properties.copy(packageName = "com.android.systemui")
    }
    private val scheduler = TestCoroutineScheduler()
    private val walker = ScreenTreeWalker(FakeClock(scheduler))
    private val routes = ScreenRoutes(WindowOrder(tree, logger), walker, NodeSearch(walker),
        NodeDescriber(NodeLookup(walker)), ScreenHasher(walker)) { if (it == "example.app") "ExampleActivity" else null }
    private val router = CommandRouter(listOf(routes), tree, gate, logger)
    private var request = 0

    init {
        tree.windowResult = PortResult.Success(listOf(window(system, 99, type = WindowType.SYSTEM), window(app)))
    }

    private suspend fun call(path: String, body: JsonObject = buildJsonObject {}): JsonObject =
        router.dispatch(BridgeCommand("11111111-2222-3333-4444-${(++request).toString().padStart(12, '0')}",
            if (path in listOf("/screen", "/screen_hash", "/current_app")) "GET" else "POST", path, body = body)).also {
            assertEquals(200, it.status)
        }.result

    @Test fun systemBarOnTopCannotHideApplicationNodesWithBooleanOrStringBoundsFlags() = runTest {
        for (flag in listOf(JsonPrimitive(false), JsonPrimitive(true), JsonPrimitive("false"), JsonPrimitive("true"))) {
            val result = call("/screen", buildJsonObject { put("include_bounds", flag) })
            assertTrue(result["success"]!!.jsonPrimitive.boolean)
            assertEquals("example.app", result["packageName"]!!.jsonPrimitive.content)
            assertTrue(result["nodeCount"]!!.jsonPrimitive.int > 0)
            val nodes = result["nodes"]!!.jsonArray.map { it.jsonObject }
            assertEquals("app content", nodes.first()["text"]!!.jsonPrimitive.content)
            assertTrue(nodes.all { it.containsKey("bounds") == flag.content.toBoolean() })
            assertEquals(listOf(17, 2), result["windows"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.int })
        }
        assertTrue(call("/screen")["nodes"]!!.jsonArray.first().jsonObject.containsKey("bounds"))
    }

    @Test fun allPackageRoutesAgreeAndCurrentAppDoesNotNeedTheDisplay() = runTest {
        val current = call("/current_app")
        assertEquals(0, gate.calls)
        assertEquals("example.app", current["package"]!!.jsonPrimitive.content)
        assertEquals("ExampleActivity", current["activity"]!!.jsonPrimitive.content)
        assertEquals(current["package"], call("/screen")["packageName"])
        assertEquals(current["package"], call("/find_nodes")["packageName"])
    }

    @Test fun idsRoundTripThroughSearchAndDescribeAndUnknownIdIsAContractFailure() = runTest {
        val id = call("/screen")["nodes"]!!.jsonArray.first().jsonObject["nodeId"]!!
        val found = call("/find_nodes", buildJsonObject { put("text", "app"); put("limit", "1") })
        assertEquals(id, found["matches"]!!.jsonArray.single().jsonObject["nodeId"])
        for (alias in listOf("nodeId", "node_id")) {
            val described = call("/describe_node", buildJsonObject { put(alias, id) })
            assertEquals(id, described["nodeId"])
            assertTrue(described.containsKey("viewIdResourceName"))
        }
        assertEquals("node_not_found", call("/describe_node", buildJsonObject { put("nodeId", "w17:999") })
            ["code"]!!.jsonPrimitive.content)
    }

    @Test fun disconnectedServiceAndReadFailuresStayFailuresThroughTheRealRouter() = runTest {
        tree.connection = PortResult.Success(false)
        assertEquals("accessibility_disabled", call("/screen")["code"]!!.jsonPrimitive.content)
        assertEquals(0, gate.calls)
        tree.connection = PortResult.Success(true)
        app.childFailure = PortFailure(FailureKind.PLATFORM, IllegalStateException("private-fixture-content"))
        assertEquals("internal_error", call("/screen")["code"]!!.jsonPrimitive.content)
        assertTrue(logger.entries.any { it.level == "error" && it.operation == "dispatch" })
        assertFalse(logger.entries.any { it.cause?.stackTraceToString()?.contains("private-fixture-content") == true })
    }

    @Test fun hashesAreStableDiffsTrackTextAndMissingPreviousHashIsBadRequest() = runTest {
        val first = call("/screen_hash")
        val hash = first["hash"]!!
        assertEquals(hash, call("/screen_hash")["hash"])
        assertFalse(call("/diff_screen", buildJsonObject { put("previous_hash", hash) })["changed"]!!.jsonPrimitive.boolean)
        app.properties = app.properties.copy(text = "changed")
        val changed = call("/diff_screen", buildJsonObject { put("previous_hash", hash) })
        assertTrue(changed["changed"]!!.jsonPrimitive.boolean)
        assertNotEquals(hash, changed["hash"])
        assertEquals(2, changed["node_count"]!!.jsonPrimitive.int)
        assertEquals("bad_request", call("/diff_screen")["code"]!!.jsonPrimitive.content)
    }

    @Test fun strictParametersRejectInvalidInputsAndSearchHonorsCombinedFilters() = runTest {
        app.properties = app.properties.copy(className = "Button", clickable = true)
        val found = call("/find_nodes", buildJsonObject {
            put("text", "APP"); put("class_name", "Button"); put("clickable", "true"); put("limit", "1")
        })
        assertEquals(1, found["count"]!!.jsonPrimitive.int)
        for (limit in listOf(0, 513)) {
            assertEquals("bad_request", call("/find_nodes", buildJsonObject { put("limit", limit) })["code"]!!.jsonPrimitive.content)
        }
        assertEquals("bad_request", call("/screen", buildJsonObject { put("include_bounds", "maybe") })["code"]!!.jsonPrimitive.content)
    }

    @Test fun findNodesReportsBudgetCutoffAndConsumerLimitSeparately() = runTest {
        repeat(20_000) { app.nodes += node("other") }
        val cutoff = call("/find_nodes", buildJsonObject { put("text", "absent") })
        assertEquals(JsonPrimitive(0), cutoff["count"])
        assertEquals(JsonPrimitive(20_000), cutoff["visited"])
        assertEquals(JsonPrimitive(true), cutoff["truncated"])
        val limited = call("/find_nodes", buildJsonObject { put("text", "app"); put("limit", 1) })
        assertEquals(JsonPrimitive(1), limited["count"])
        assertEquals(JsonPrimitive(1), limited["visited"])
        assertEquals(JsonPrimitive(false), limited["truncated"])
    }

    @Test fun timeBudgetCutoffStillReturnsAResultWithItsReadOutcome() = runTest(scheduler) {
        app.nodes += node("unvisited")
        val slow = object : UiNode by app {
            override suspend fun children(): PortResult<List<UiNode>> {
                scheduler.advanceTimeBy(MAX_SEARCH_MS)
                return app.children()
            }
        }
        tree.windowResult = PortResult.Success(listOf(window(app).copy(root = slow)))
        val result = call("/find_nodes", buildJsonObject { put("text", "absent") })
        assertEquals(JsonPrimitive(true), result["success"])
        assertEquals(JsonPrimitive(0), result["count"])
        assertEquals(JsonPrimitive(1), result["visited"])
        assertEquals(JsonPrimitive(true), result["truncated"])
    }

    @Test fun screenCapAndHashResponseFieldsRemainIndependentOfTheSearchBudget() = runTest {
        repeat(600) { app.nodes += node("entry") }
        val screen = call("/screen")
        assertEquals(JsonPrimitive(512), screen["nodeCount"])
        assertEquals(JsonPrimitive(true), screen["truncated"])
        assertEquals(setOf("packageName", "rootBounds", "nodes", "nodeCount", "truncated", "windows", "success"), screen.keys)
        val hash = call("/screen_hash")
        assertEquals(setOf("hash", "node_count", "truncated", "success"), hash.keys)
        assertEquals(JsonPrimitive(512), hash["node_count"])
        assertEquals(JsonPrimitive(true), hash["truncated"])
    }
}
