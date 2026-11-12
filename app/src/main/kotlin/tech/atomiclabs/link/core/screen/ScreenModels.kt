// Ported from upstream app/src/main/kotlin/com/hermesandroid/relay/accessibility/ScreenReader.kt at commit da782a8.
package tech.atomiclabs.link.core.screen

import kotlinx.serialization.json.*
import tech.atomiclabs.link.core.port.*

const val MAX_SCREEN_NODES = 512
const val MAX_SEARCH_VISITS = 20_000
const val MAX_SEARCH_MS = 20_000L
const val MAX_TEXT_LEN = 2000

data class ScreenWindow(val window: UiWindow?, val root: UiNode, val properties: UiNodeProperties)
data class ScreenSnapshot(val windows: List<ScreenWindow>, val packageName: String?)
data class LocatedNode(val nodeId: String, val node: UiNode, val properties: UiNodeProperties)
data class VisitOutcome(val visited: Int, val truncated: Boolean)
data class ScreenHash(val hash: String, val nodeCount: Int, val truncated: Boolean)

data class ScreenNode(val nodeId: String, val properties: UiNodeProperties) {
    fun toJson(includeBounds: Boolean = true): JsonObject = buildJsonObject {
        put("nodeId", nodeId)
        put("text", properties.text.visibleText())
        put("contentDescription", properties.contentDescription.visibleText())
        put("className", properties.className)
        put("viewId", properties.viewId)
        put("clickable", properties.clickable)
        put("longClickable", properties.longClickable)
        put("scrollable", properties.scrollable)
        put("editable", properties.editable)
        put("focused", properties.focused)
        put("selected", properties.selected)
        put("enabled", properties.enabled)
        if (includeBounds) put("bounds", properties.bounds.toJson())
    }
}

data class ScreenContent(
    val snapshot: ScreenSnapshot,
    val rootBounds: Bounds,
    val nodes: List<ScreenNode>,
    val truncated: Boolean,
) {
    fun toJson(includeBounds: Boolean): JsonObject = buildJsonObject {
        put("packageName", snapshot.packageName)
        put("rootBounds", rootBounds.toJson())
        put("nodes", JsonArray(nodes.map { it.toJson(includeBounds) }))
        put("nodeCount", nodes.size)
        put("truncated", truncated)
        put("windows", JsonArray(snapshot.windows.mapNotNull { entry ->
            val window = entry.window ?: return@mapNotNull null
            buildJsonObject {
                put("id", window.id)
                put("type", window.platformType)
                put("package", entry.properties.packageName)
                put("layer", window.layer)
                put("active", window.active)
                put("focused", window.focused)
            }
        }))
    }
}

internal fun Bounds.toJson(detailed: Boolean = false): JsonObject = buildJsonObject {
    put("left", left); put("top", top); put("right", right); put("bottom", bottom)
    if (detailed) {
        put("centerX", (left + right) / 2); put("centerY", (top + bottom) / 2)
        put("width", right - left); put("height", bottom - top)
    }
}

internal fun String?.visibleText(): String? = this?.takeIf { it.isNotBlank() }?.take(MAX_TEXT_LEN)

internal fun <T> PortResult<T>.valueOrThrow(): T = when (this) {
    is PortResult.Success -> value
    is PortResult.Failure -> throw ScreenReadException(failure)
}

class ScreenReadException(val failure: PortFailure) :
    IllegalStateException("The accessibility tree could not be read.", failure.cause)
