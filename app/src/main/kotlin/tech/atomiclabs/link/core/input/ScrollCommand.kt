// Ported from upstream app/src/main/kotlin/com/hermesandroid/relay/accessibility/ActionExecutor.kt at commit da782a8.
package tech.atomiclabs.link.core.input

import kotlinx.serialization.json.*
import tech.atomiclabs.link.core.command.*
import tech.atomiclabs.link.core.log.Logger
import tech.atomiclabs.link.core.port.*
import tech.atomiclabs.link.core.screen.*

class ScrollCommand(
    private val windows: WindowOrder, private val lookup: NodeLookup, private val geometry: SwipeGeometry,
    private val gestures: GestureInput, private val logger: Logger,
) {
    suspend fun execute(body: BodyParams): CommandResult {
        val direction = body.requiredString("direction").lowercase()
        val actions = when (direction) {
            "down" -> listOf(NodeActionKind.SCROLL_DOWN, NodeActionKind.SCROLL_FORWARD)
            "up" -> listOf(NodeActionKind.SCROLL_UP, NodeActionKind.SCROLL_BACKWARD)
            "left" -> listOf(NodeActionKind.SCROLL_LEFT, NodeActionKind.SCROLL_BACKWARD)
            "right" -> listOf(NodeActionKind.SCROLL_RIGHT, NodeActionKind.SCROLL_FORWARD)
            else -> throw BadRequestException("direction")
        }
        val snapshot = windows.snapshot()
        val id = body.string("nodeId")
        val node = if (id == null) null else lookup.find(snapshot, id)
            ?: inputFail(ErrorCode.NODE_NOT_FOUND, "The requested scroll node was not found.")
        val action = node?.properties?.supportedActions?.let { supported -> actions.firstOrNull { it in supported } }
        if (node != null && action != null) {
            node.node.perform(NodeAction.Simple(action)).inputValue(ErrorCode.GESTURE_FAILED, "The node refused scrolling.")
            return CommandResult.Success(buildJsonObject { put("direction", direction); put("via", "node_action") })
        }
        val bounds = if (node != null) node.properties.bounds else {
            val window = snapshot.windows.firstOrNull()
                ?: inputFail(ErrorCode.NODE_NOT_FOUND, "There is no active window to scroll.")
            window.window?.bounds ?: window.properties.bounds
        }
        logger.warn("ScrollCommand", "swipeFallback", "state=no_node_scroll_action")
        val finger = when (direction) { "up" -> "down"; "down" -> "up"; "left" -> "right"; else -> "left" }
        val stroke = geometry.inside(bounds, finger)
        gestures.dispatch(listOf(stroke)).inputValue(ErrorCode.GESTURE_FAILED, "The scroll gesture failed or was cancelled.")
        return CommandResult.Success(buildJsonObject { put("direction", direction); put("via", "gesture") })
    }
}
