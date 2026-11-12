package tech.atomiclabs.link.device.access

import android.graphics.Rect
import android.os.Bundle
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import io.mockk.*
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import tech.atomiclabs.link.core.fakes.FakeLogger
import tech.atomiclabs.link.core.port.*

@OptIn(ExperimentalCoroutinesApi::class)
class AndroidUiNodeTest {
    @Test fun snapshotReadsRealWindowIdBoundsAndFullStateAndWrapsRelatedNodes() = runTest {
        val raw = mockk<AccessibilityNodeInfo>(relaxed = true)
        val child = mockk<AccessibilityNodeInfo>(relaxed = true)
        every { raw.windowId } returns 83
        every { child.windowId } returns 83
        every { raw.getBoundsInScreen(any()) } answers {
            firstArg<Rect>().apply { left = 2; top = 3; right = 40; bottom = 50 }
        }
        every { raw.text } returns "text"
        every { raw.packageName } returns "example.app"
        every { raw.isCheckable } returns true
        every { raw.isChecked } returns false
        every { raw.childCount } returns 2
        every { raw.getChild(0) } returns child
        every { raw.getChild(1) } returns null
        every { raw.parent } returns child
        every { raw.focusSearch(View.FOCUS_FORWARD) } returns child
        val action = mockk<AccessibilityNodeInfo.AccessibilityAction>()
        every { action.id } returns AccessibilityNodeInfo.ACTION_CLICK
        every { raw.actionList } returns listOf(action)
        val node = AndroidUiNode(raw, FakeLogger(), StandardTestDispatcher(testScheduler))
        val properties = (node.read() as PortResult.Success).value
        assertEquals(83, properties.windowId)
        assertEquals(Bounds(2, 3, 40, 50), properties.bounds)
        assertEquals("text", properties.text)
        assertEquals(false, properties.checked)
        assertEquals(setOf(NodeActionKind.CLICK), properties.supportedActions)
        assertEquals(1, (node.children() as PortResult.Success).value.size)
        assertTrue(node.parent() is ReadValue.Present)
        assertTrue(node.nextInputFocus() is ReadValue.Present)
        every { raw.isCheckable } returns false
        assertNull((node.read() as PortResult.Success).value.checked)
    }

    @Test fun absentRelationsDifferFromFailedReadsAndPrivateExceptionMessagesAreRedacted() = runTest {
        val raw = mockk<AccessibilityNodeInfo>(relaxed = true)
        every { raw.parent } returns null
        every { raw.focusSearch(any()) } returns null
        val logger = FakeLogger()
        val node = AndroidUiNode(raw, logger, StandardTestDispatcher(testScheduler))
        assertEquals(ReadValue.Absent, node.parent())
        assertEquals(ReadValue.Absent, node.nextInputFocus())
        val cause = SecurityException("private-node-text")
        every { raw.windowId } throws cause
        val failed = node.read() as PortResult.Failure
        assertTrue(generateSequence(failed.failure.cause) { it.cause }.any { it === cause })
        assertEquals(FailureKind.PERMISSION_DENIED, failed.failure.kind)
        every { raw.parent } throws cause
        assertTrue(node.parent() is ReadValue.Unreadable)
        every { raw.childCount } throws cause
        assertTrue(node.children() is PortResult.Failure)
        assertEquals(3, logger.entries.count { it.level == "error" })
        assertFalse(logger.entries.any { it.cause!!.stackTraceToString().contains("private-node-text") })
    }

    @Test fun simpleActionsUseTheActualAndroidIdsAndReturnCheckedSuccess() = runTest {
        val raw = mockk<AccessibilityNodeInfo>(relaxed = true)
        val logger = FakeLogger()
        val node = AndroidUiNode(raw, logger, StandardTestDispatcher(testScheduler))
        val actions = listOf(
            NodeActionKind.CLICK to AccessibilityNodeInfo.ACTION_CLICK,
            NodeActionKind.LONG_CLICK to AccessibilityNodeInfo.ACTION_LONG_CLICK,
            NodeActionKind.FOCUS to AccessibilityNodeInfo.ACTION_FOCUS,
            NodeActionKind.CLEAR_FOCUS to AccessibilityNodeInfo.ACTION_CLEAR_FOCUS,
            NodeActionKind.SET_TEXT to AccessibilityNodeInfo.ACTION_SET_TEXT,
            NodeActionKind.SET_SELECTION to AccessibilityNodeInfo.ACTION_SET_SELECTION,
            NodeActionKind.IME_ENTER to android.R.id.accessibilityActionImeEnter,
            NodeActionKind.SCROLL_FORWARD to AccessibilityNodeInfo.ACTION_SCROLL_FORWARD,
            NodeActionKind.SCROLL_BACKWARD to AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD,
            NodeActionKind.SCROLL_UP to android.R.id.accessibilityActionScrollUp,
            NodeActionKind.SCROLL_DOWN to android.R.id.accessibilityActionScrollDown,
            NodeActionKind.SCROLL_LEFT to android.R.id.accessibilityActionScrollLeft,
            NodeActionKind.SCROLL_RIGHT to android.R.id.accessibilityActionScrollRight,
        )
        assertEquals(NodeActionKind.entries.toSet(), actions.map { it.first }.toSet())
        every { raw.performAction(any()) } returns true
        for ((kind, id) in actions) {
            assertEquals(PortResult.Success(Unit), node.perform(NodeAction.Simple(kind)))
            verify(exactly = 1) { raw.performAction(id) }
        }
        assertTrue(logger.entries.isEmpty())
    }

    @Test fun textAndSelectionUseTheirRequiredBundleKeysAndThePlatformReceivesThoseArguments() = runTest {
        mockkConstructor(Bundle::class)
        try {
            every { anyConstructed<Bundle>().putCharSequence(any(), any()) } just Runs
            every { anyConstructed<Bundle>().putInt(any(), any()) } just Runs
            val raw = mockk<AccessibilityNodeInfo>()
            val arguments = mutableListOf<Bundle>()
            every { raw.performAction(any(), capture(arguments)) } returns true
            val logger = FakeLogger()
            val node = AndroidUiNode(raw, logger, StandardTestDispatcher(testScheduler))
            assertEquals(PortResult.Success(Unit), node.perform(NodeAction.SetText("private typed fixture")))
            assertEquals(PortResult.Success(Unit), node.perform(NodeAction.SetSelection(2, 5)))
            assertEquals(2, arguments.size)
            assertNotSame(arguments[0], arguments[1])
            verifyOrder {
                anyConstructed<Bundle>().putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "private typed fixture")
                raw.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments[0])
                anyConstructed<Bundle>().putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 2)
                anyConstructed<Bundle>().putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, 5)
                raw.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, arguments[1])
            }
            assertTrue(logger.entries.isEmpty())
        } finally { unmockkConstructor(Bundle::class) }
    }

    @Test fun refusedPermissionAndPlatformActionsAreTypedAndKeepCausesOutOfLogs() = runTest {
        val raw = mockk<AccessibilityNodeInfo>()
        val logger = FakeLogger()
        val node = AndroidUiNode(raw, logger, StandardTestDispatcher(testScheduler))
        every { raw.performAction(any()) } returns false
        assertEquals(FailureKind.REJECTED,
            (node.perform(NodeAction.Simple(NodeActionKind.CLICK)) as PortResult.Failure).failure.kind)
        for (cause in listOf(SecurityException("private typed fixture"), IllegalStateException("private typed fixture"))) {
            every { raw.performAction(any()) } throws cause
            val failed = node.perform(NodeAction.Simple(NodeActionKind.CLICK)) as PortResult.Failure
            assertEquals(if (cause is SecurityException) FailureKind.PERMISSION_DENIED else FailureKind.PLATFORM, failed.failure.kind)
            assertTrue(generateSequence(failed.failure.cause) { it.cause }.any { it === cause })
        }
        assertEquals(3, logger.entries.size)
        assertTrue(logger.entries.all { it.level == "error" && it.operation == "perform" && it.cause != null })
        assertFalse(logger.entries.any { it.cause!!.stackTraceToString().contains("private typed fixture") })
        assertFalse(logger.entries.any { it.context.contains("private typed fixture") })
    }

    @Test fun queuedActionsHaveAFiveSecondDeadlineAndOuterCancellationDoesNotBecomeFailure() = runTest {
        val queued = ArrayDeque<Runnable>()
        val dispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) { queued.addLast(block) }
        }
        val raw = mockk<AccessibilityNodeInfo>()
        val logger = FakeLogger()
        val node = AndroidUiNode(raw, logger, dispatcher)
        val timed = async { node.perform(NodeAction.Simple(NodeActionKind.CLICK)) }
        runCurrent(); advanceTimeBy(4_999); runCurrent()
        assertFalse(timed.isCompleted)
        advanceTimeBy(1); runCurrent(); queued.removeFirst().run(); runCurrent()
        assertEquals(FailureKind.TIMED_OUT, (timed.await() as PortResult.Failure).failure.kind)
        assertEquals(5_000, currentTime)
        val logged = logger.entries.size
        val cancelled = async { withTimeout(25) { node.perform(NodeAction.Simple(NodeActionKind.CLICK)) } }
        runCurrent(); advanceTimeBy(25); runCurrent(); queued.removeFirst().run(); runCurrent()
        cancelled.join()
        assertTrue(cancelled.isCancelled)
        assertEquals(logged, logger.entries.size)
        verify(exactly = 0) { raw.performAction(any()); raw.performAction(any(), any()) }
    }
}
