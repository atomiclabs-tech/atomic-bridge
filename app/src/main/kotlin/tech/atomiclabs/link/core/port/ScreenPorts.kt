// forge-allow: no-test declarations only
package tech.atomiclabs.link.core.port

/** Raw windows retain real bounds/order metadata; disconnection and stale/read failures stay explicit. */
interface UiTreeSource {
    suspend fun isConnected(): PortResult<Boolean>
    suspend fun windows(): PortResult<List<UiWindow>>
    suspend fun activeRoot(): ReadValue<UiNode>
    suspend fun inputFocus(): ReadValue<UiNode>
}

/** Node handles expose snapshots and checked actions; absent parents/focus differ from unreadable nodes. */
interface UiNode {
    suspend fun read(): PortResult<UiNodeProperties>
    suspend fun children(): PortResult<List<UiNode>>
    suspend fun parent(): ReadValue<UiNode>
    suspend fun nextInputFocus(): ReadValue<UiNode>
    suspend fun perform(action: NodeAction): PortResult<Unit>
}

/** Current default-display pixel metrics, or an explicit platform/read failure. */
fun interface ScreenMetrics { suspend fun read(): PortResult<DisplayMetrics> }

/** Read-time window snapshot; null root is absent content, while read failures belong to UiTreeSource. */
data class UiWindow(
    val id: Int,
    val type: WindowType,
    val platformType: Int,
    val layer: Int,
    val active: Boolean,
    val focused: Boolean,
    val root: UiNode?,
    val bounds: Bounds,
)

enum class WindowType { APPLICATION, INPUT_METHOD, SYSTEM, ACCESSIBILITY_OVERLAY, SPLIT_SCREEN_DIVIDER, OTHER }
data class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int)
data class DisplayMetrics(val width: Int, val height: Int, val density: Float)

/** AndroidUiNode must read windowId from AccessibilityNodeInfo.windowId, including for the active-root fallback. */
data class UiNodeProperties(
    val bounds: Bounds,
    val windowId: Int,
    val packageName: String? = null,
    val text: String? = null,
    val contentDescription: String? = null,
    val className: String? = null,
    val viewId: String? = null,
    val hintText: String? = null,
    val childCount: Int = 0,
    val clickable: Boolean = false,
    val longClickable: Boolean = false,
    val scrollable: Boolean = false,
    val editable: Boolean = false,
    val focusable: Boolean = false,
    val focused: Boolean = false,
    val selected: Boolean = false,
    val enabled: Boolean = true,
    val checkable: Boolean = false,
    val checked: Boolean? = null,
    val password: Boolean = false,
    val selectionStart: Int = -1,
    val selectionEnd: Int = -1,
    val showingHintText: Boolean = false,
    val supportedActions: Set<NodeActionKind> = emptySet(),
)

enum class NodeActionKind {
    CLICK, LONG_CLICK, FOCUS, CLEAR_FOCUS, SET_TEXT, SET_SELECTION, IME_ENTER,
    SCROLL_FORWARD, SCROLL_BACKWARD, SCROLL_UP, SCROLL_DOWN, SCROLL_LEFT, SCROLL_RIGHT,
}

sealed interface NodeAction {
    data class Simple(val kind: NodeActionKind) : NodeAction
    data class SetText(val text: String) : NodeAction
    data class SetSelection(val start: Int, val end: Int) : NodeAction
}
