package tech.atomiclabs.link.core.audio

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import tech.atomiclabs.link.core.fakes.*
import tech.atomiclabs.link.core.port.*

class SpeakerControlTest {
    @Test fun clicksOneVisibleEnabledSpeakerAndPreservesActionFailure() = runTest {
        val node = FakeUiNode(UiNodeProperties(Bounds(0, 0, 100, 100), 1, contentDescription = "Speaker", clickable = true))
        val tree = FakeUiTree().apply { root = ReadValue.Present(node) }
        assertEquals(PortResult.Success(Unit), SpeakerControl(tree).click())
        assertEquals(listOf(NodeAction.Simple(NodeActionKind.CLICK)), node.actions)
        val denied = PortResult.Failure(PortFailure(FailureKind.REJECTED, IllegalStateException("click rejected")))
        node.actionResult = denied
        assertEquals(denied, SpeakerControl(tree).click())
    }
    @Test fun skipsDisabledInvisibleAndUnrelatedControlsAndBoundsCyclicTrees() = runTest {
        val node = FakeUiNode(UiNodeProperties(Bounds(0, 0, 100, 100), 1, text = "Speaker", clickable = true, enabled = false))
        node.nodes += node
        val tree = FakeUiTree().apply { root = ReadValue.Present(node) }
        assertTrue(SpeakerControl(tree).click() is PortResult.Failure)
        node.properties = node.properties.copy(enabled = true, bounds = Bounds(0, 0, 0, 0))
        assertTrue(SpeakerControl(tree).click() is PortResult.Failure)
        node.properties = node.properties.copy(bounds = Bounds(0, 0, 10, 10), text = "Other")
        assertTrue(SpeakerControl(tree).click() is PortResult.Failure)
        assertTrue(node.actions.isEmpty())
        val failure = PortResult.Failure(PortFailure(FailureKind.UNAVAILABLE, IllegalStateException("tree unavailable")))
        tree.windowResult = failure
        assertEquals(failure, SpeakerControl(tree).click())
    }
}
