package tech.atomiclabs.link.core.input

import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import tech.atomiclabs.link.core.command.*
import tech.atomiclabs.link.core.fakes.*
import tech.atomiclabs.link.core.port.*
import tech.atomiclabs.link.core.protocol.BridgeCommand
import tech.atomiclabs.link.core.screen.*

internal fun inputBody(vararg values: Pair<String, Any>): JsonObject = buildJsonObject {
    for ((key, value) in values) put(key, when (value) {
        is String -> JsonPrimitive(value); is Boolean -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value); else -> error("Unsupported fixture scalar")
    })
}
internal fun params(vararg values: Pair<String, Any>) = BodyParams(inputBody(*values))
internal val refused = PortResult.Failure(PortFailure(FailureKind.REJECTED, IllegalStateException("private fixture payload")))

internal class InputGestures : GestureInput {
    val calls = mutableListOf<List<GestureStroke>>()
    var result: PortResult<Unit> = PortResult.Success(Unit)
    override suspend fun dispatch(strokes: List<GestureStroke>): PortResult<Unit> { calls += strokes; return result }
}
internal class InputGlobals : GlobalKeys {
    val calls = mutableListOf<GlobalAction>()
    var result: PortResult<Unit> = PortResult.Success(Unit)
    override suspend fun perform(action: GlobalAction): PortResult<Unit> { calls += action; return result }
}
internal class InputVolume : VolumeControl {
    val calls = mutableListOf<VolumeDirection>()
    override suspend fun adjust(direction: VolumeDirection): PortResult<Unit> { calls += direction; return PortResult.Success(Unit) }
}
internal class InputPower : PowerState {
    var interactive = true
    var wakes = 0
    var wakesScreen = true
    override suspend fun isInteractive(): PortResult<Boolean> = PortResult.Success(interactive)
    override suspend fun wakeWithBrightLock(durationMs: Long): PortResult<Unit> {
        assertEquals(2_000, durationMs); wakes++; interactive = wakesScreen; return PortResult.Success(Unit)
    }
}
internal class InputFixture(scheduler: TestCoroutineScheduler) {
    val logger = FakeLogger()
    val root = FakeUiNode(UiNodeProperties(Bounds(0, 0, 1080, 2400), 11, text = "root"))
    val tree = FakeUiTree().apply {
        root = ReadValue.Present(this@InputFixture
                .root)
        windowResult = PortResult.Success(listOf(UiWindow(11, WindowType.APPLICATION, 1, 1, true, true,
            this@InputFixture
                .root, Bounds(0, 0, 1080, 2400))))
    }
    val gestures = InputGestures(); val globals = InputGlobals(); val volume = InputVolume(); val power = InputPower()
    var metrics: PortResult<DisplayMetrics> = PortResult.Success(DisplayMetrics(1080, 2400, 3f))
    val geometry = SwipeGeometry { metrics }
    private val windows = WindowOrder(tree, logger)
    private val walker = ScreenTreeWalker(FakeClock(scheduler))
    private val lookup = NodeLookup(walker)
    val text = TextEntry(tree, logger)
    val keys = KeyCommands(globals, volume, power, text, FakeClock(scheduler))
    val group = InputRoutes(TapCommands(gestures, windows, walker, lookup, logger), text, geometry,
        ScrollCommand(windows, lookup, geometry, gestures, logger), keys,
        WaitCommand(windows, walker, FakeClock(scheduler)), gestures, logger)
    val gate = FakeDisplayGate()
    val router = CommandRouter(listOf(group), tree, gate, logger)
    private var nextId = 0
    suspend fun send(path: String, vararg values: Pair<String, Any>, id: String = "input-${++nextId}"): JsonObject {
        val response = router.dispatch(BridgeCommand(id, "POST", path, body = inputBody(*values)))
        assertEquals(200, response.status)
        return response.result
    }
}

class InputRoutesTest {
    @Test fun allNinePostRoutesUseTheSharedPreconditionsAndReplayOnlyTheFirstAction() = runTest {
        val f = InputFixture(testScheduler)
        assertEquals(setOf("/tap", "/tap_text", "/long_press", "/type", "/swipe", "/drag", "/scroll", "/press_key", "/wait"),
            f.group.routes.map { it.path }.toSet())
        assertTrue(f.group.routes.all { it.method == "POST" })
        f.tree.connection = PortResult.Success(false)
        assertEquals("accessibility_disabled", f.send("/tap", "x" to 4, "y" to 8)["code"]!!.jsonPrimitive.content)
        assertEquals(0, f.gate.calls)
        f.tree.connection = PortResult.Success(true)
        f.gate.result = DisplayGateResult.Failed(CommandResult.Failure(ErrorCode.WAKE_FAILED, "The display stayed off."))
        assertEquals("wake_failed", f.send("/tap", "x" to 4, "y" to 8)["code"]!!.jsonPrimitive.content)
        f.gate.result = DisplayGateResult.Ready
        val first = f.send("/tap", "x" to 4, "y" to 8, id = "repeat")
        assertEquals(first, f.send("/tap", "x" to 99, "y" to 99, id = "repeat"))
        assertEquals(1, f.gestures.calls.size)
    }

    @Test fun badParametersAndDependencyFailuresStayObjectsWithoutPrivateLogs() = runTest {
        val f = InputFixture(testScheduler)
        assertEquals("bad_request", f.send("/tap", "x" to 3)["code"]!!.jsonPrimitive.content)
        f.gestures.result = refused
        val failed = f.send("/tap", "x" to 3, "y" to 4)
        assertEquals("gesture_failed", failed["code"]!!.jsonPrimitive.content)
        assertFalse(failed["success"]!!.jsonPrimitive.boolean)
        assertFalse(f.logger.entries.any { it.context.contains("private fixture payload") ||
            it.cause?.stackTraceToString()?.contains("private fixture payload") == true })
    }

    @Test fun dragEchoesDefaultHoldAndReplaysTheFirstGestureOnce() = runTest {
        val f = InputFixture(testScheduler)
        val result = f.send("/drag", "start_x" to 1, "start_y" to 2, "end_x" to 8, "end_y" to 9, id = "drag-once")
        assertEquals("{\"start_x\":1,\"start_y\":2,\"end_x\":8,\"end_y\":9,\"duration_ms\":500,\"hold_ms\":0,\"success\":true}",
            result.toString())
        assertEquals(result, f.send("/drag", "start_x" to 4, "start_y" to 5, "end_x" to 6, "end_y" to 7,
            "hold_ms" to 800, id = "drag-once"))
        assertEquals(1, f.gestures.calls.size)
    }

    @Test fun swipeAndTapResponseBytesStayUnchanged() = runTest {
        val f = InputFixture(testScheduler)
        assertEquals("{\"start_x\":1,\"start_y\":2,\"end_x\":8,\"end_y\":9,\"duration_ms\":300,\"success\":true}",
            f.send("/swipe", "start_x" to 1, "start_y" to 2, "end_x" to 8, "end_y" to 9).toString())
        assertEquals("{\"x\":4,\"y\":8,\"duration_ms\":100,\"success\":true}",
            f.send("/tap", "x" to 4, "y" to 8).toString())
    }

    @Test fun heldDragFailureIsReturnedAndLoggedWithoutRepeatingTheGesture() = runTest {
        val f = InputFixture(testScheduler)
        f.gestures.result = refused
        val result = f.send("/drag", "start_x" to 1, "start_y" to 2, "end_x" to 8, "end_y" to 9,
            "hold_ms" to 800, id = "failed-drag")
        assertEquals(JsonPrimitive("gesture_failed"), result["code"])
        assertEquals(result, f.send("/drag", "start_x" to 1, "start_y" to 2, "end_x" to 8, "end_y" to 9,
            "hold_ms" to 800, id = "failed-drag"))
        assertEquals(800, f.gestures.calls.single().single().holdMs)
        assertTrue(f.logger.entries.any { it.level == "error" && it.cause != null })
    }
}
