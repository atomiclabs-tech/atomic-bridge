// Ported from upstream app/src/main/kotlin/com/hermesandroid/relay/accessibility/ScreenReader.kt at commit da782a8.
package tech.atomiclabs.link.core.screen

import kotlinx.serialization.json.*

class NodeDescriber(private val lookup: NodeLookup) {
    suspend fun describe(snapshot: ScreenSnapshot, nodeId: String): JsonObject? {
        val node = lookup.find(snapshot, nodeId) ?: return null
        val properties = node.properties
        return buildJsonObject {
            put("nodeId", node.nodeId)
            put("bounds", properties.bounds.toJson(detailed = true))
            put("className", properties.className?.takeIf { it.isNotBlank() })
            put("text", properties.text.visibleText())
            put("contentDescription", properties.contentDescription.visibleText())
            put("hintText", properties.hintText?.takeIf { it.isNotBlank() })
            put("viewIdResourceName", properties.viewId?.takeIf { it.isNotBlank() })
            put("childCount", properties.childCount)
            put("clickable", properties.clickable); put("longClickable", properties.longClickable)
            put("focusable", properties.focusable); put("focused", properties.focused)
            put("editable", properties.editable); put("scrollable", properties.scrollable)
            put("checkable", properties.checkable)
            put("checked", if (properties.checkable) properties.checked?.let(::JsonPrimitive) ?: JsonNull else JsonNull)
            put("enabled", properties.enabled); put("selected", properties.selected)
            put("password", properties.password)
        }
    }
}
