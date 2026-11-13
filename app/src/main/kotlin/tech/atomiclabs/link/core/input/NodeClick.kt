// Ported from upstream app/src/main/kotlin/com/hermesandroid/relay/accessibility/ActionExecutor.kt at commit da782a8.
package tech.atomiclabs.link.core.input

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import tech.atomiclabs.link.core.command.ErrorCode
import tech.atomiclabs.link.core.port.*
import tech.atomiclabs.link.core.screen.LocatedNode
import tech.atomiclabs.link.core.screen.MAX_SEARCH_VISITS

/** Clicks the nearest clickable ancestor; otherwise taps the original match's centre. */
class NodeClick(private val gestures: GestureInput) {
    data class Result(val via: String, val point: Point)

    /** Sensitive input uses the exact verified key, without ancestor or coordinate redirection. */
    suspend fun clickExact(found: LocatedNode, onDispatch: () -> Unit): Result {
        val current = found.node.read().inputValue(ErrorCode.GESTURE_FAILED, "The key could not be read.")
        if (!current.clickable || !current.enabled || current.packageName != found.properties.packageName ||
            current.windowId != found.properties.windowId || current.viewId != found.properties.viewId ||
            current.password || current.editable || current.text != found.properties.text ||
            current.contentDescription != found.properties.contentDescription || current.bounds != found.properties.bounds) {
            inputFail(ErrorCode.GESTURE_FAILED, "The key no longer belongs to the verified pad.")
        }
        currentCoroutineContext().ensureActive()
        onDispatch()
        found.node.perform(NodeAction.Simple(NodeActionKind.CLICK))
            .inputValue(ErrorCode.GESTURE_FAILED, "The key refused the click.")
        return Result("direct click", SwipeGeometry.center(current.bounds))
    }

    suspend fun click(found: LocatedNode, onCoordinateFallback: () -> Unit): Result {
        var node: UiNode? = found.node
        var properties = found.properties
        for (depth in 0 until MAX_SEARCH_VISITS) {
            currentCoroutineContext().ensureActive()
            val current = node ?: break
            if (properties.clickable) {
                current.perform(NodeAction.Simple(NodeActionKind.CLICK))
                    .inputValue(ErrorCode.GESTURE_FAILED, "The matching node refused the click.")
                return Result(if (depth == 0) "direct click" else "parent click ($depth levels up)",
                    SwipeGeometry.center(properties.bounds))
            }
            node = current.parent().inputNode("The matching node's parent could not be read.")
            if (node != null) properties = node.read().inputValue(ErrorCode.GESTURE_FAILED, "The parent could not be read.")
        }
        if (node != null) inputFail(ErrorCode.GESTURE_FAILED, "The clickable ancestor search exceeded its limit.")
        onCoordinateFallback()
        val point = SwipeGeometry.center(found.properties.bounds)
        gestures.dispatch(listOf(GestureStroke(listOf(point), 100)))
            .inputValue(ErrorCode.GESTURE_FAILED, "The tap gesture failed or was cancelled.")
        return Result("coordinate fallback", point)
    }
}
