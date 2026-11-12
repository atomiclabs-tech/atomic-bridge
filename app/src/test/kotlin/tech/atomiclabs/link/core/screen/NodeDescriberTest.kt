package tech.atomiclabs.link.core.screen

import tech.atomiclabs.link.core.fakes.FakeClock
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class NodeDescriberTest {
    @Test fun fullPropertyBagPreservesNullCheckedAndDetailedBounds() = runTest {
        val raw = node()
        raw.properties = raw.properties.copy(hintText = "hint", viewId = "app:id/field", childCount = 3,
            focusable = true, focused = true, editable = true, selected = true, password = true)
        val describer = NodeDescriber(NodeLookup(ScreenTreeWalker(FakeClock(testScheduler))))
        val result = describer.describe(snapshot(raw), "w7:0")!!
        assertEquals(JsonNull, result["checked"])
        assertEquals("hint", result["hintText"]!!.jsonPrimitive.content)
        assertEquals(29, result["bounds"]!!.jsonObject["width"]!!.jsonPrimitive.int)
        assertEquals(3, result["childCount"]!!.jsonPrimitive.int)
        assertTrue(result["password"]!!.jsonPrimitive.boolean)
        raw.properties = raw.properties.copy(checkable = true, checked = false)
        assertFalse(describer.describe(snapshot(raw), "w7:0")!!["checked"]!!.jsonPrimitive.boolean)
        assertNull(describer.describe(snapshot(raw), "w7:9"))
    }
}
