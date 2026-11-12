// Ported from upstream app/src/main/kotlin/com/hermesandroid/relay/accessibility/ActionExecutor.kt at commit da782a8.
package tech.atomiclabs.link.core.input

import kotlinx.serialization.json.*
import tech.atomiclabs.link.core.command.*
import tech.atomiclabs.link.core.log.Logger
import tech.atomiclabs.link.core.port.*

class TextEntry(private val tree: UiTreeSource, private val logger: Logger) {
    suspend fun type(body: BodyParams): CommandResult {
        val text = body.requiredString("text")
        val (node, properties) = focused()
        val old = content(properties)
        val replace = body.boolean("clearFirst", false)
        val (start, end) = if (replace) 0 to old.length else selection(properties, old)
        write(node, old.replaceRange(start, end, text), start + text.length)
        return CommandResult.Success(buildJsonObject { put("length", text.length) })
    }

    suspend fun delete() {
        val (node, properties) = focused()
        val old = content(properties)
        val (start, end) = selection(properties, old)
        if (end == 0) return
        val from = if (start < end) start else {
            val pair = start >= 2 && old[start - 1].isLowSurrogate() && old[start - 2].isHighSurrogate()
            start - if (pair) 2 else 1
        }
        write(node, old.removeRange(from, end), from)
    }

    suspend fun enter() {
        focused().first.perform(NodeAction.Simple(NodeActionKind.IME_ENTER))
            .inputValue(ErrorCode.GESTURE_FAILED, "The input refused the enter action.")
    }

    suspend fun tab() {
        val node = focused(editable = false).first.nextInputFocus().inputNode("The next input could not be read.")
            ?: inputFail(ErrorCode.NO_FOCUSED_INPUT, "There is no next input focus.")
        node.perform(NodeAction.Simple(NodeActionKind.FOCUS))
            .inputValue(ErrorCode.GESTURE_FAILED, "The next input refused focus.")
    }

    private suspend fun focused(editable: Boolean = true): Pair<UiNode, UiNodeProperties> {
        val node = tree.inputFocus().inputNode("The input focus could not be read.")
            ?: inputFail(ErrorCode.NO_FOCUSED_INPUT, "There is no focused input.")
        val properties = node.read().inputValue(ErrorCode.GESTURE_FAILED, "The focused input could not be read.")
        if (editable && !properties.editable) inputFail(ErrorCode.NO_FOCUSED_INPUT, "There is no focused editable input.")
        return node to properties
    }

    private fun content(properties: UiNodeProperties): String = if (properties.showingHintText) "" else properties.text.orEmpty()

    private fun selection(properties: UiNodeProperties, text: String): Pair<Int, Int> {
        if (text.isEmpty()) return 0 to 0
        val start = properties.selectionStart; val end = properties.selectionEnd
        if (start !in 0..text.length || end !in 0..text.length) {
            inputFail(ErrorCode.GESTURE_FAILED, "The input selection is unavailable.")
        }
        return minOf(start, end) to maxOf(start, end)
    }

    private suspend fun write(node: UiNode, text: String, cursor: Int) {
        node.perform(NodeAction.SetText(text)).inputValue(ErrorCode.GESTURE_FAILED, "The input refused the text change.")
        restoreCursorBestEffort(node, cursor)
    }

    private suspend fun restoreCursorBestEffort(node: UiNode, cursor: Int) {
        val result = node.perform(NodeAction.SetSelection(cursor, cursor))
        if (result is PortResult.Failure && result.failure.kind == FailureKind.REJECTED) {
            logger.warn("TextEntry", "restoreCursorBestEffort", "state=declined kind=REJECTED",
                FailureDiagnostics.sanitized(result.failure.cause))
        } else result.inputValue(ErrorCode.GESTURE_FAILED, "The input refused the cursor change.")
    }
}
