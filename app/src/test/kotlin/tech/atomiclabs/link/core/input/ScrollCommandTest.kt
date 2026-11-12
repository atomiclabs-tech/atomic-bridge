package tech.atomiclabs.link.core.input

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import tech.atomiclabs.link.core.port.*

class ScrollCommandTest {
    @Test fun supportedNodeActionsRevealTheRequestedContentWithoutAGesture() = runTest {
        val f = InputFixture(testScheduler)
        val actions = listOf("up" to NodeActionKind.SCROLL_UP, "down" to NodeActionKind.SCROLL_DOWN,
            "left" to NodeActionKind.SCROLL_LEFT, "right" to NodeActionKind.SCROLL_RIGHT)
        f.root.properties = f.root.properties.copy(scrollable = true, supportedActions = actions.map { it.second }.toSet())
        for ((direction, action) in actions) {
            f.send("/scroll", "nodeId" to "w11:0", "direction" to direction)
            assertEquals(NodeAction.Simple(action), f.root.actions.last())
        }
        f.root.properties = f.root.properties.copy(supportedActions = setOf(NodeActionKind.SCROLL_FORWARD))
        f.send("/scroll", "node_id" to "w11:0", "direction" to "down")
        assertEquals(NodeAction.Simple(NodeActionKind.SCROLL_FORWARD), f.root.actions.last())
        assertTrue(f.gestures.calls.isEmpty())
    }

    @Test fun fallbackStaysInsideNodeOrActualWindowBoundsAndUsesOppositeFingerMovement() = runTest {
        val f = InputFixture(testScheduler)
        f.root.properties = f.root.properties.copy(bounds = Bounds(100, 200, 300, 600))
        f.send("/scroll", "node_id" to "w11:0", "direction" to "down")
        assertEquals(listOf(Point(200f, 500f), Point(200f, 300f)), f.gestures.calls.last().single().points)
        for ((direction, expected) in listOf("down" to listOf(Point(540f, 1800f), Point(540f, 600f)),
            "up" to listOf(Point(540f, 600f), Point(540f, 1800f)),
            "left" to listOf(Point(270f, 1200f), Point(810f, 1200f)),
            "right" to listOf(Point(810f, 1200f), Point(270f, 1200f)))) {
            f.send("/scroll", "direction" to direction)
            assertEquals(expected, f.gestures.calls.last().single().points)
        }
        assertEquals(5, f.logger.entries.count { it.operation == "swipeFallback" })
    }

    @Test fun unknownNodesAndRefusedActionsDoNotBecomeFallbackSuccess() = runTest {
        val f = InputFixture(testScheduler)
        assertEquals("node_not_found", f.send("/scroll", "direction" to "up", "nodeId" to "missing")["code"]!!.jsonPrimitive.content)
        f.root.properties = f.root.properties.copy(supportedActions = setOf(NodeActionKind.SCROLL_UP))
        f.root.actionResult = refused
        assertEquals("gesture_failed", f.send("/scroll", "direction" to "up", "nodeId" to "w11:0")["code"]!!.jsonPrimitive.content)
        assertTrue(f.gestures.calls.isEmpty())
    }
}
