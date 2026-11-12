package tech.atomiclabs.link.core.input

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import tech.atomiclabs.link.core.fakes.FakeUiNode
import tech.atomiclabs.link.core.port.*

class TapCommandsTest {
    @Test fun tapAndLongPressAcceptBothNodeAndDurationAliasesAndCoordinateForms() = runTest {
        val f = InputFixture(testScheduler)
        for (alias in listOf("nodeId", "node_id")) {
            val tap = f.send("/tap", alias to "w11:0")
            assertEquals(540, tap["x"]!!.jsonPrimitive.int); assertEquals(1200, tap["y"]!!.jsonPrimitive.int)
            assertEquals(100, tap["duration_ms"]!!.jsonPrimitive.int)
            for (duration in listOf("duration", "duration_ms")) {
                val long = f.send("/long_press", alias to "w11:0", duration to 600)
                assertEquals(600, long["duration_ms"]!!.jsonPrimitive.int)
                assertEquals("action_long_click", long["mode"]!!.jsonPrimitive.content)
            }
        }
        assertEquals(4, f.root.actions.size)
        val long = f.send("/long_press", "x" to 17, "y" to 23)
        assertEquals(500, long["duration_ms"]!!.jsonPrimitive.int)
        assertEquals("gesture", long["mode"]!!.jsonPrimitive.content)
        assertEquals(listOf(Point(17f, 23f)), f.gestures.calls.last().single().points)
        for (duration in listOf(99, 3001)) assertEquals("bad_request",
            f.send("/long_press", "x" to 1, "y" to 2, "duration" to duration)["code"]!!.jsonPrimitive.content)
        assertEquals("node_not_found", f.send("/tap", "node_id" to "w11:99")["code"]!!.jsonPrimitive.content)
    }

    @Test fun exactAndContainsSearchPreferTheNearestClickableAncestor() = runTest {
        val f = InputFixture(testScheduler)
        f.root.properties = f.root.properties.copy(clickable = true)
        val child = FakeUiNode(UiNodeProperties(Bounds(20, 20, 60, 60), 11, text = "Hello World", contentDescription = "other target"))
        child.parentNode = ReadValue.Present(f.root); f.root.nodes += child
        assertTrue(f.send("/tap_text", "text" to "HELLO")["success"]!!.jsonPrimitive.boolean)
        assertEquals(NodeAction.Simple(NodeActionKind.CLICK), f.root.actions.single())
        assertTrue(f.gestures.calls.isEmpty())
        assertEquals("node_not_found", f.send("/tap_text", "text" to "HELLO", "exact" to true)["code"]!!.jsonPrimitive.content)
        assertTrue(f.send("/tap_text", "text" to "hello WORLD", "exact" to true)["success"]!!.jsonPrimitive.boolean)
        assertTrue(f.send("/tap_text", "text" to "OTHER TARGET", "exact" to true)["success"]!!.jsonPrimitive.boolean)
    }

    @Test fun textMatchClicksTheAncestorAboveTwentyIntermediateNodes() = runTest {
        val f = InputFixture(testScheduler)
        f.root.properties = f.root.properties.copy(clickable = true)
        var parent = f.root
        repeat(20) {
            val child = FakeUiNode(UiNodeProperties(Bounds(10, 10, 100, 100), 11))
            child.parentNode = ReadValue.Present(parent); parent.nodes += child
            parent = child
        }
        val leaf = FakeUiNode(UiNodeProperties(Bounds(20, 40, 60, 80), 11, text = "deep target"))
        leaf.parentNode = ReadValue.Present(parent); parent.nodes += leaf
        val result = f.send("/tap_text", "text" to "DEEP TARGET", "exact" to true)
        assertTrue(result["success"]!!.jsonPrimitive.boolean)
        assertEquals("parent click (21 levels up)", result["via"]!!.jsonPrimitive.content)
        assertEquals(540, result["x"]!!.jsonPrimitive.int); assertEquals(1200, result["y"]!!.jsonPrimitive.int)
        assertEquals(NodeAction.Simple(NodeActionKind.CLICK), f.root.actions.single())
        assertTrue(leaf.actions.isEmpty()); assertTrue(f.gestures.calls.isEmpty())
    }

    @Test fun unfinishedAncestorSearchFailsWithoutClaimingAbsenceOrTapping() = runTest {
        val f = InputFixture(testScheduler)
        val child = FakeUiNode(UiNodeProperties(Bounds(20, 40, 60, 80), 11, text = "target"))
        f.root.nodes += child; child.parentNode = ReadValue.Present(child)
        assertEquals("gesture_failed", f.send("/tap_text", "text" to "target")["code"]!!.jsonPrimitive.content)
        assertTrue(child.actions.isEmpty()); assertTrue(f.gestures.calls.isEmpty())
        assertFalse(f.logger.entries.any { it.operation == "tapTextCoordinateFallback" })
    }

    @Test fun coordinateFallbackIsAtTheMatchedNodeAndWarnsWhileRefusalsStayFailures() = runTest {
        val f = InputFixture(testScheduler)
        val child = FakeUiNode(UiNodeProperties(Bounds(20, 40, 60, 80), 11, text = "target"))
        f.root.nodes += child
        val result = f.send("/tap_text", "text" to "target")
        assertEquals(40, result["x"]!!.jsonPrimitive.int); assertEquals(60, result["y"]!!.jsonPrimitive.int)
        assertEquals("target", result["needle"]!!.jsonPrimitive.content)
        assertEquals(1, f.logger.entries.count { it.operation == "tapTextCoordinateFallback" && it.level == "warn" })
        f.gestures.result = refused
        assertEquals("gesture_failed", f.send("/tap_text", "text" to "target")["code"]!!.jsonPrimitive.content)
        child.properties = child.properties.copy(clickable = true); child.actionResult = refused
        val count = f.gestures.calls.size
        assertEquals("gesture_failed", f.send("/tap_text", "text" to "target")["code"]!!.jsonPrimitive.content)
        assertEquals(count, f.gestures.calls.size)
    }

    @Test fun emptyTextDismissDescriptionClicksItsNearestClickableParent() = runTest {
        val f = InputFixture(testScheduler)
        f.root.properties = f.root.properties.copy(clickable = true)
        val child = FakeUiNode(UiNodeProperties(Bounds(20, 20, 60, 60), 11, text = "", contentDescription = "Dismiss"))
        child.parentNode = ReadValue.Present(f.root)
        f.root.nodes += child
        val result = f.send("/tap_text", "text" to "Dismiss", "exact" to true)
        assertEquals(JsonPrimitive(true), result["success"])
        assertEquals(JsonPrimitive("parent click (1 levels up)"), result["via"])
        assertEquals(listOf(NodeAction.Simple(NodeActionKind.CLICK)), f.root.actions)
        assertTrue(child.actions.isEmpty())
        assertTrue(f.gestures.calls.isEmpty())
    }

    @Test fun normalizedCompatibilityQueriesClickForContainsAndExact() = runTest {
        val f = InputFixture(testScheduler)
        f.root.properties = f.root.properties.copy(text = "℃", clickable = true)
        assertEquals(JsonPrimitive(true), f.send("/tap_text", "text" to "°C")["success"])
        assertEquals(JsonPrimitive(true), f.send("/tap_text", "text" to "°C", "exact" to true)["success"])
        assertEquals(2, f.root.actions.size)
        assertTrue(f.gestures.calls.isEmpty())
    }

    @Test fun normalizedWhitespaceExactQueryClicksTheMatchingText() = runTest {
        val f = InputFixture(testScheduler)
        f.root.properties = f.root.properties.copy(text = "Me\u00a0at the zoo", clickable = true)
        assertEquals(JsonPrimitive(true), f.send("/tap_text", "text" to "me at the zoo", "exact" to true)["success"])
        assertEquals(1, f.root.actions.size)
        assertTrue(f.gestures.calls.isEmpty())
    }

    @Test fun targetAfterSixThousandVisitsStillClicksTheNearestParent() = runTest {
        val f = InputFixture(testScheduler)
        f.root.properties = f.root.properties.copy(clickable = true)
        repeat(6000) { f.root.nodes += FakeUiNode(UiNodeProperties(Bounds(0, 0, 10, 10), 11)) }
        val target = FakeUiNode(UiNodeProperties(Bounds(20, 20, 60, 60), 11, text = "late target"))
        target.parentNode = ReadValue.Present(f.root)
        f.root.nodes += target
        val result = f.send("/tap_text", "text" to "late target")
        assertEquals(JsonPrimitive(true), result["success"])
        assertEquals(JsonPrimitive("parent click (1 levels up)"), result["via"])
        assertEquals(1, f.root.actions.size)
        assertTrue(f.gestures.calls.isEmpty())
    }

    @Test fun unmatchedTextReportsWhetherTheVisitBudgetCutOffTheSearch() = runTest {
        val f = InputFixture(testScheduler)
        val complete = f.send("/tap_text", "text" to "absent")
        assertEquals(JsonPrimitive("node_not_found"), complete["code"])
        assertEquals(JsonPrimitive(false), complete["truncated"])
        assertEquals(JsonPrimitive(1), complete["visited"])
        repeat(20_000) { f.root.nodes += FakeUiNode(UiNodeProperties(Bounds(0, 0, 10, 10), 11)) }
        val cutoff = f.send("/tap_text", "text" to "absent")
        assertEquals(JsonPrimitive("node_not_found"), cutoff["code"])
        assertEquals(JsonPrimitive(true), cutoff["truncated"])
        assertEquals(JsonPrimitive(20_000), cutoff["visited"])
        assertTrue(f.root.actions.isEmpty())
        assertTrue(f.gestures.calls.isEmpty())
    }
}
