package tech.atomiclabs.link.core.screen

import org.junit.Assert.*
import org.junit.Test
import tech.atomiclabs.link.core.port.Bounds

class NodeFilterTest {
    @Test fun everySupportedSemanticSignalUsesTheRealNonemptyRectangle() {
        val empty = node(null).properties
        assertFalse(NodeFilter.emits(empty))
        assertFalse(NodeFilter.emits(empty.copy(text = "  ", contentDescription = "\n")))
        for (interesting in listOf(empty.copy(text = "text"), empty.copy(contentDescription = "label"),
            empty.copy(clickable = true), empty.copy(longClickable = true), empty.copy(scrollable = true),
            empty.copy(editable = true))) {
            assertTrue(NodeFilter.emits(interesting))
            assertFalse(NodeFilter.emits(interesting.copy(bounds = Bounds(1, 1, 1, 5))))
            assertFalse(NodeFilter.emits(interesting.copy(bounds = Bounds(1, 5, 8, 4))))
        }
    }
}
