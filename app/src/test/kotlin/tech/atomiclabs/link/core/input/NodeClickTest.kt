package tech.atomiclabs.link.core.input

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import tech.atomiclabs.link.core.fakes.FakeUiNode
import tech.atomiclabs.link.core.port.*
import tech.atomiclabs.link.core.screen.LocatedNode

class NodeClickTest {
    private fun located(node: FakeUiNode) = LocatedNode("fixture", node, node.properties)
    private fun node(clickable: Boolean = false) = FakeUiNode(UiNodeProperties(Bounds(20, 40, 60, 80), 1,
        clickable = clickable))

    @Test fun directClickReturnsTheSameViaAndCentreWithoutAGesture() = runTest {
        val node = node(clickable = true)
        val gestures = InputGestures()
        val result = NodeClick(gestures).click(located(node)) { fail("A direct click must not fall back.") }
        assertEquals("direct click", result.via)
        assertEquals(Point(40f, 60f), result.point)
        assertEquals(listOf(NodeAction.Simple(NodeActionKind.CLICK)), node.actions)
        assertTrue(gestures.calls.isEmpty())
    }

    @Test fun exactClicksNeverUseAncestorsAndRejectChangedOwnershipBeforeDispatch() = runTest {
        val gestures = InputGestures()
        val click = NodeClick(gestures)
        for (change in listOf("package", "window", "enabled", "clickable", "id", "label", "bounds")) {
            val key = node(clickable = true)
            val original = located(key)
            key.properties = when (change) {
                "package" -> key.properties.copy(packageName = "another.app")
                "window" -> key.properties.copy(windowId = 99)
                "enabled" -> key.properties.copy(enabled = false)
                "clickable" -> key.properties.copy(clickable = false)
                "id" -> key.properties.copy(viewId = "different-key")
                "label" -> key.properties.copy(text = "changed-label")
                else -> key.properties.copy(bounds = Bounds(0, 0, 1, 1))
            }
            key.parentNode = ReadValue.Present(node(clickable = true))
            var dispatched = false
            try {
                click.clickExact(original) { dispatched = true }
                fail("An untrusted exact key must be refused.")
            } catch (cause: InputFailure) {
                assertEquals("gesture_failed", cause.result.code.wireValue)
            }
            assertFalse(dispatched)
            assertTrue(key.actions.isEmpty())
            assertTrue(gestures.calls.isEmpty())
        }
        val key = node(clickable = true)
        var dispatched = false
        click.clickExact(located(key)) { dispatched = true }
        assertTrue(dispatched)
        assertEquals(1, key.actions.size)
    }

    @Test fun nearestClickableAncestorWinsAndItsCentreIsReturned() = runTest {
        val leaf = node()
        val parent = node(clickable = true).apply { properties = properties.copy(bounds = Bounds(0, 0, 100, 200)) }
        val outer = node(clickable = true)
        leaf.parentNode = ReadValue.Present(parent); parent.parentNode = ReadValue.Present(outer)
        val result = NodeClick(InputGestures()).click(located(leaf)) { fail("An ancestor must not fall back.") }
        assertEquals("parent click (1 levels up)", result.via)
        assertEquals(Point(50f, 100f), result.point)
        assertEquals(1, parent.actions.size)
        assertTrue(leaf.actions.isEmpty() && outer.actions.isEmpty())
    }

    @Test fun coordinateFallbackKeepsTheOriginalMatchCentreAndReportsBeforeDispatch() = runTest {
        val leaf = node()
        val parent = node().apply { properties = properties.copy(bounds = Bounds(0, 0, 100, 200)) }
        leaf.parentNode = ReadValue.Present(parent)
        var reported = false
        val gestures = GestureInput {
            assertTrue(reported)
            assertEquals(listOf(GestureStroke(listOf(Point(40f, 60f)), 100)), it)
            PortResult.Success(Unit)
        }
        val result = NodeClick(gestures).click(located(leaf)) { reported = true }
        assertEquals("coordinate fallback", result.via)
        assertEquals(Point(40f, 60f), result.point)
    }

    @Test fun refusedClicksAndGesturesKeepTheirCauseWithoutRetrying() = runTest {
        for (clickable in listOf(false, true)) {
            val leaf = node(clickable)
            val cause = IllegalStateException("refused fixture action")
            val failure = PortResult.Failure(PortFailure(FailureKind.REJECTED, cause))
            leaf.actionResult = failure
            val gestures = InputGestures().apply { result = failure }
            try {
                NodeClick(gestures).click(located(leaf)) {}
                fail("A refused outside call must fail.")
            } catch (thrown: InputFailure) { assertSame(cause, thrown.cause) }
            assertEquals(if (clickable) 1 else 0, leaf.actions.size)
            assertEquals(if (clickable) 0 else 1, gestures.calls.size)
        }
    }

    @Test fun unreadableParentsFailWithoutAGestureAndRetainTheCause() = runTest {
        val leaf = node()
        val cause = IllegalStateException("unreadable fixture parent")
        leaf.parentNode = ReadValue.Unreadable(PortFailure(FailureKind.PLATFORM, cause))
        val gestures = InputGestures()
        try {
            NodeClick(gestures).click(located(leaf)) { fail("An unreadable parent is not absence.") }
            fail("The unreadable parent must fail.")
        } catch (thrown: InputFailure) { assertSame(cause, thrown.cause) }
        assertTrue(gestures.calls.isEmpty())
    }

    @Test fun cyclicAncestorsStopAtTheExistingBoundWithoutATap() = runTest {
        val leaf = node().apply { parentNode = ReadValue.Present(this) }
        val gestures = InputGestures()
        try {
            NodeClick(gestures).click(located(leaf)) { fail("An unfinished walk is not absence.") }
            fail("The ancestor limit must fail.")
        } catch (thrown: InputFailure) { assertEquals("gesture_failed", thrown.result.code.wireValue) }
        assertTrue(gestures.calls.isEmpty())
    }
}
