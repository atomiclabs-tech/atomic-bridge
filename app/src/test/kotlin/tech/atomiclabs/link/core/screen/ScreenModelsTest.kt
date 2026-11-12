package tech.atomiclabs.link.core.screen

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import tech.atomiclabs.link.core.fakes.FakeUiNode
import tech.atomiclabs.link.core.port.*

internal fun node(text: String? = "visible", id: Int = 7, bounds: Bounds = Bounds(1, 2, 30, 40)): FakeUiNode =
    FakeUiNode(UiNodeProperties(bounds, windowId = id, text = text, packageName = "example.app"))

internal fun window(root: FakeUiNode, layer: Int = 1, active: Boolean = true,
    type: WindowType = WindowType.APPLICATION, focused: Boolean = false): UiWindow =
    UiWindow(root.properties.windowId, type, if (type == WindowType.APPLICATION) 1 else 3,
        layer, active, focused, root, bounds = Bounds(0, 0, 100, 200))

internal fun snapshot(vararg roots: FakeUiNode): ScreenSnapshot = ScreenSnapshot(
    roots.map { ScreenWindow(window(it), it, it.properties) }, roots.firstOrNull()?.properties?.packageName,
)

class ScreenModelsTest {
    @Test fun outputOmitsOnlyNodeBoundsAndTruncatesTextsAtTheWireBoundary() {
        val raw = node("a".repeat(2100))
        raw.properties = raw.properties.copy(contentDescription = "b".repeat(2200))
        val model = ScreenNode("w7:0", raw.properties)
        assertFalse(model.toJson(false).containsKey("bounds"))
        assertEquals(raw.properties.bounds.toJson(), model.toJson()["bounds"])
        assertEquals(2000, model.toJson()["text"]!!.jsonPrimitive.content.length)
        assertEquals(2000, model.toJson()["contentDescription"]!!.jsonPrimitive.content.length)
        assertEquals(2100, raw.properties.text!!.length)
    }

    @Test fun metadataDescribesRealWindowsAndDoesNotFabricateAnActiveRootWindow() {
        val raw = node()
        val content = ScreenContent(snapshot(raw), raw.properties.bounds, listOf(ScreenNode("w7:0", raw.properties)), false)
        val json = content.toJson(false)
        assertEquals(1, json["nodeCount"]!!.jsonPrimitive.int)
        assertEquals(7, json["windows"]!!.jsonArray.single().jsonObject["id"]!!.jsonPrimitive.int)
        assertEquals(raw.properties.bounds.toJson(), json["rootBounds"])
        val fallback = ScreenSnapshot(listOf(ScreenWindow(null, raw, raw.properties)), "example.app")
        assertTrue(content.copy(snapshot = fallback).toJson(true)["windows"]!!.jsonArray.isEmpty())
    }

    @Test fun portFailuresKeepTheirTypedMetadataAndCause() {
        val cause = IllegalStateException("fixture")
        val failure = PortFailure(FailureKind.PLATFORM, cause, platformCode = 8)
        val exception = assertThrows(ScreenReadException::class.java) { PortResult.Failure(failure).valueOrThrow() }
        assertSame(cause, exception.cause)
        assertSame(failure, exception.failure)
    }
}
