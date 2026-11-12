// Ported from upstream app/src/main/kotlin/com/hermesandroid/relay/accessibility/ActionExecutor.kt at commit da782a8.
package tech.atomiclabs.link.core.input

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*
import tech.atomiclabs.link.core.command.*
import tech.atomiclabs.link.core.log.Logger
import tech.atomiclabs.link.core.port.*
import tech.atomiclabs.link.core.screen.*

class TapCommands(
    private val gestures: GestureInput,
    private val windows: WindowOrder,
    private val walker: ScreenTreeWalker,
    private val lookup: NodeLookup,
    private val logger: Logger,
) {
    suspend fun tap(body: BodyParams, longPress: Boolean = false): CommandResult {
        val duration = body.long("duration_ms", if (longPress) 500 else 100)
        if (longPress && duration !in 100..3000) throw BadRequestException("duration_ms")
        SwipeGeometry.positiveDuration(duration)
        val id = body.string("nodeId")
        if (id != null && (body.element("x") != null || body.element("y") != null)) throw BadRequestException("nodeId")
        val target = if (id == null) null else lookup.find(windows.snapshot(), id)
            ?: inputFail(ErrorCode.NODE_NOT_FOUND, "The requested node was not found.")
        val point = if (target != null) SwipeGeometry.center(target.properties.bounds) else {
            val x = body.requiredInt("x"); val y = body.requiredInt("y")
            if (x < 0 || y < 0) throw BadRequestException("x")
            Point(x.toFloat(), y.toFloat())
        }
        val mode = if (longPress && target != null) {
            target.node.perform(NodeAction.Simple(NodeActionKind.LONG_CLICK))
                .inputValue(ErrorCode.GESTURE_FAILED, "The node refused the long press.")
            "action_long_click"
        } else {
            gesture(point, duration)
            "gesture"
        }
        return CommandResult.Success(buildJsonObject {
            put("x", point.x.toInt()); put("y", point.y.toInt()); put("duration_ms", duration)
            if (longPress) put("mode", mode)
        })
    }

    suspend fun tapText(body: BodyParams): CommandResult {
        val needle = body.requiredString("text")
        if (needle.isBlank()) throw BadRequestException("text")
        val exact = body.boolean("exact", false)
        var match: LocatedNode? = null
        val outcome = walker.visit(windows.snapshot(), MAX_SEARCH_VISITS) { candidate ->
            if (TextMatch.matchesNode(candidate.properties, needle, exact)) match = candidate
            match == null
        }
        val found = match ?: throw InputFailure(CommandResult.Failure(ErrorCode.NODE_NOT_FOUND, "No matching text was found.",
            buildJsonObject { put("truncated", outcome.truncated); put("visited", outcome.visited) }))
        var node: UiNode? = found.node
        var properties = found.properties
        var via = "coordinate fallback"
        var point = SwipeGeometry.center(properties.bounds)
        var clicked = false
        for (depth in 0 until MAX_SEARCH_VISITS) {
            currentCoroutineContext().ensureActive()
            val current = node ?: break
            if (properties.clickable) {
                current.perform(NodeAction.Simple(NodeActionKind.CLICK))
                    .inputValue(ErrorCode.GESTURE_FAILED, "The matching node refused the click.")
                point = SwipeGeometry.center(properties.bounds)
                via = if (depth == 0) "direct click" else "parent click ($depth levels up)"
                clicked = true
                break
            }
            node = current.parent().inputNode("The matching node's parent could not be read.")
            if (node != null) properties = node.read().inputValue(ErrorCode.GESTURE_FAILED, "The parent could not be read.")
        }
        if (!clicked) {
            if (node != null) inputFail(ErrorCode.GESTURE_FAILED, "The clickable ancestor search exceeded its limit.")
            logger.warn("TapCommands", "tapTextCoordinateFallback", "state=no_clickable_ancestor")
            gesture(point, 100)
        }
        return CommandResult.Success(buildJsonObject {
            put("via", via); put("x", point.x.toInt()); put("y", point.y.toInt()); put("needle", needle)
        })
    }

    private suspend fun gesture(point: Point, duration: Long) {
        gestures.dispatch(listOf(GestureStroke(listOf(point), duration)))
            .inputValue(ErrorCode.GESTURE_FAILED, "The tap gesture failed or was cancelled.")
    }
}
