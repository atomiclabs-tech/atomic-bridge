package tech.atomiclabs.link.core.unlock

import tech.atomiclabs.link.core.fakes.FakeUiNode
import tech.atomiclabs.link.core.fakes.FakeUiTree
import tech.atomiclabs.link.core.port.*

/** Layout labels represent keycaps only; entered sequences are generated inside individual tests. */
object PinPadFixtures {
    class Pad(val root: FakeUiNode, val digits: Map<Char, FakeUiNode>, val confirm: FakeUiNode?,
              val clicks: MutableList<FakeUiNode>) {
        fun tree() = FakeUiTree().also { tree ->
            tree.windowResult = PortResult.Success(listOf(UiWindow(1, WindowType.SYSTEM, 1, 1, true, true,
                root, root.properties.bounds)))
        }
    }

    fun oneUi(missing: Char? = null): Pad = build(pixel = false, confirm = true, missing = missing)
    fun pixel(confirm: Boolean = true): Pad = build(pixel = true, confirm = confirm)

    private fun build(pixel: Boolean, confirm: Boolean, missing: Char? = null): Pad {
        val root = FakeUiNode(UiNodeProperties(Bounds(0, 0, 1080, 2400), 1,
            className = "android.widget.FrameLayout", packageName = "com.android.systemui",
            viewId = "com.android.systemui:id/keyguard_pin_view"))
        val clicked = mutableListOf<FakeUiNode>()
        fun attach(properties: UiNodeProperties): FakeUiNode {
            val key = FakeUiNode(properties.copy(packageName = "com.android.systemui")).apply { parentNode = ReadValue.Present(root) }
            root.nodes += object : UiNode by key {
                override suspend fun perform(action: NodeAction): PortResult<Unit> {
                    clicked += key
                    return key.perform(action)
                }
            }
            return key
        }
        val digits = ('0'..'9').filter { it != missing }.associateWith { digit ->
            attach(UiNodeProperties(Bounds(20, 20, 120, 120), 1, clickable = true,
                text = if (pixel) null else digit.toString(),
                contentDescription = if (pixel) digit.toString() else null,
                viewId = if (pixel) "com.android.systemui:id/key$digit" else null))
        }
        val enter = if (!confirm) null else attach(UiNodeProperties(Bounds(20, 20, 120, 120), 1, clickable = true,
            className = if (pixel) "android.widget.ImageButton" else "android.widget.Button",
            text = if (pixel) null else "OK", contentDescription = if (pixel) "Enter" else null,
            viewId = if (pixel) "com.android.systemui:id/key_enter" else null))
        for (label in listOf("Delete", "Cancel")) attach(UiNodeProperties(Bounds(20, 20, 120, 120), 1,
            clickable = true, text = label))
        return Pad(root, digits, enter, clicked)
    }
}
