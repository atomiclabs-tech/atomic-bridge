package tech.atomiclabs.link.core.input

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import tech.atomiclabs.link.core.fakes.FakeUiNode
import tech.atomiclabs.link.core.port.*

class TextEntryTest {
    @Test fun appliedTextSucceedsAfterCursorRefusalAndReplayDoesNotMutateAgain() = runTest {
        val payload = "private-typed-value"
        for (replace in listOf(false, true)) {
            val f = InputFixture(testScheduler)
            f.root.properties = f.root.properties.copy(editable = true, text = "abcd", selectionStart = 1, selectionEnd = 3)
            val node = ApplyingInputNode(f.root)
            node.selectionResult = refused
            f.tree.focus = ReadValue.Present(node)
            val result = f.send("/type", "text" to payload, "clearFirst" to replace, id = "applied")
            assertTrue(result["success"]!!.jsonPrimitive.boolean)
            assertEquals(payload.length, result["length"]!!.jsonPrimitive.int)
            assertEquals(if (replace) payload else "a${payload}d", f.root.properties.text)
            assertEquals(1, f.root.actions.count { it is NodeAction.SetText })
            assertEquals(1, f.root.actions.count { it is NodeAction.SetSelection })
            assertEquals(result, f.send("/type", "text" to "another-private-value", id = "applied"))
            assertEquals(1, f.root.actions.count { it is NodeAction.SetText })
            assertTrue(f.gestures.calls.isEmpty())
            val warning = f.logger.entries.single { it.level == "warn" }
            assertEquals("TextEntry", warning.component)
            assertEquals("restoreCursorBestEffort", warning.operation)
            val logged = checkNotNull(warning.cause)
            assertArrayEquals(refused.failure.cause.stackTrace, logged.stackTrace)
            for (marker in listOf(payload, "another-private-value", "private fixture payload")) {
                assertFalse(warning.context.contains(marker))
                assertFalse(logged.stackTraceToString().contains(marker))
            }
            f.send("/type", "text" to payload, "clearFirst" to true)
            assertEquals(2, f.logger.entries.count { it.operation == "restoreCursorBestEffort" })
        }
    }

    @Test fun appliedDeleteSucceedsAfterCursorRefusalForUnicodeAndSelectedContent() = runTest {
        for ((start, end, expected) in listOf(Triple(4, 4, "abc"), Triple(1, 4, "ac"))) {
            val f = InputFixture(testScheduler)
            f.root.properties = f.root.properties.copy(editable = true, text = "ab😀c", selectionStart = start, selectionEnd = end)
            val node = ApplyingInputNode(f.root)
            node.selectionResult = refused; f.tree.focus = ReadValue.Present(node)
            val result = f.send("/press_key", "key" to "delete")
            assertTrue(result["success"]!!.jsonPrimitive.boolean)
            assertEquals(expected, f.root.properties.text)
            assertEquals(1, f.root.actions.count { it is NodeAction.SetText })
            assertEquals(1, f.logger.entries.count { it.operation == "restoreCursorBestEffort" })
        }
    }

    @Test fun textRefusalAndUnreadableInputStillFailWithoutCursorFallback() = runTest {
        val f = InputFixture(testScheduler)
        f.root.properties = f.root.properties.copy(editable = true, text = "old", selectionStart = 1, selectionEnd = 1)
        val node = ApplyingInputNode(f.root)
        node.textResult = refused; f.tree.focus = ReadValue.Present(node)
        assertEquals("gesture_failed", f.send("/type", "text" to "new")["code"]!!.jsonPrimitive.content)
        assertEquals("old", f.root.properties.text)
        assertEquals(listOf(NodeAction.SetText("onewld")), f.root.actions)
        f.root.actions.clear(); f.root.readFailure = refused.failure
        assertEquals("gesture_failed", f.send("/type", "text" to "new")["code"]!!.jsonPrimitive.content)
        assertTrue(f.root.actions.isEmpty())
        assertFalse(f.logger.entries.any { it.operation == "restoreCursorBestEffort" })
    }

    @Test fun cursorPlatformFailureStaysAnErrorAndCancellationPropagatesAfterOneWrite() = runTest {
        val f = InputFixture(testScheduler)
        f.root.properties = f.root.properties.copy(editable = true, text = "old", selectionStart = 1, selectionEnd = 1)
        val node = ApplyingInputNode(f.root)
        node.selectionResult = PortResult.Failure(PortFailure(FailureKind.PLATFORM, IllegalStateException("private-platform")))
        f.tree.focus = ReadValue.Present(node)
        assertEquals("gesture_failed", f.send("/type", "text" to "new", "clearFirst" to true)["code"]!!.jsonPrimitive.content)
        assertEquals("new", f.root.properties.text)
        f.root.actions.clear()
        val cancellation = CancellationException("private-cancellation")
        node.selectionException = cancellation
        try {
            f.text.type(params("text" to "next", "clearFirst" to true))
            fail("Cancellation must propagate")
        } catch (cause: CancellationException) { assertSame(cancellation, cause) }
        assertEquals(1, f.root.actions.count { it is NodeAction.SetText })
        assertFalse(f.logger.entries.any { it.operation == "restoreCursorBestEffort" })
    }

    @Test fun insertionReplacesSelectionAndBothClearAliasesReplaceAllContent() = runTest {
        val f = InputFixture(testScheduler)
        f.root.properties = f.root.properties.copy(editable = true, text = "abcd", selectionStart = 1, selectionEnd = 3)
        f.tree.focus = ReadValue.Present(f.root)
        val inserted = f.send("/type", "text" to "X", "clearFirst" to false)
        assertEquals(1, inserted["length"]!!.jsonPrimitive.int)
        assertEquals(listOf(NodeAction.SetText("aXd"), NodeAction.SetSelection(2, 2)), f.root.actions)
        for (alias in listOf("clearFirst", "clear_first")) {
            f.root.actions.clear()
            f.send("/type", "text" to "new", alias to true)
            assertEquals(listOf(NodeAction.SetText("new"), NodeAction.SetSelection(3, 3)), f.root.actions)
        }
        f.root.actions.clear()
        f.root.properties = f.root.properties.copy(selectionStart = 2, selectionEnd = 2)
        f.send("/type", "text" to "X", "clear_first" to false)
        assertEquals(NodeAction.SetText("abXcd"), f.root.actions.first())
    }

    @Test fun displayedHintIsEmptyAndMissingOrUnreadableFocusNeverBecomesTextSuccess() = runTest {
        val f = InputFixture(testScheduler)
        f.root.properties = f.root.properties.copy(editable = true, text = "hint", hintText = "hint", showingHintText = true)
        f.tree.focus = ReadValue.Present(f.root)
        f.send("/type", "text" to "new")
        assertEquals(NodeAction.SetText("new"), f.root.actions.first())
        f.tree.focus = ReadValue.Absent
        assertEquals("no_focused_input", f.send("/type", "text" to "private fixture payload")["code"]!!.jsonPrimitive.content)
        for (key in listOf("enter", "delete", "tab")) assertEquals("no_focused_input",
            f.send("/press_key", "key" to key)["code"]!!.jsonPrimitive.content)
        f.tree.focus = ReadValue.Unreadable(refused.failure)
        assertEquals("gesture_failed", f.send("/type", "text" to "private fixture payload")["code"]!!.jsonPrimitive.content)
    }

    @Test fun deleteHandlesASelectionAndAUnicodeCharacterAndEnterAndTabUseCheckedActions() = runTest {
        val f = InputFixture(testScheduler)
        f.tree.focus = ReadValue.Present(f.root)
        f.root.properties = f.root.properties.copy(editable = true, text = "ab😀c", selectionStart = 4, selectionEnd = 4)
        f.send("/press_key", "key" to "delete")
        assertEquals(listOf(NodeAction.SetText("abc"), NodeAction.SetSelection(2, 2)), f.root.actions)
        f.root.actions.clear(); f.root.properties = f.root.properties.copy(selectionStart = 1, selectionEnd = 4)
        f.send("/press_key", "key" to "delete")
        assertEquals(NodeAction.SetText("ac"), f.root.actions.first())
        f.root.actions.clear(); f.send("/press_key", "key" to "enter")
        assertEquals(listOf(NodeAction.Simple(NodeActionKind.IME_ENTER)), f.root.actions)
        val next = FakeUiNode(f.root.properties)
        f.root.nextFocus = ReadValue.Present(next)
        f.send("/press_key", "key" to "tab")
        assertEquals(listOf(NodeAction.Simple(NodeActionKind.FOCUS)), next.actions)
        f.root.actionResult = refused
        assertEquals("gesture_failed", f.send("/type", "text" to "new")["code"]!!.jsonPrimitive.content)
    }
}

private class ApplyingInputNode(private val delegate: FakeUiNode) : UiNode by delegate {
    var textResult: PortResult<Unit> = PortResult.Success(Unit)
    var selectionResult: PortResult<Unit> = PortResult.Success(Unit)
    var selectionException: CancellationException? = null
    override suspend fun perform(action: NodeAction): PortResult<Unit> {
        delegate.actions += action
        return when (action) {
            is NodeAction.SetText -> {
                if (textResult is PortResult.Success) delegate.properties = delegate.properties.copy(text = action.text)
                textResult
            }
            is NodeAction.SetSelection -> {
                selectionException?.let { throw it }
                selectionResult
            }
            else -> delegate.actionResult
        }
    }
}
