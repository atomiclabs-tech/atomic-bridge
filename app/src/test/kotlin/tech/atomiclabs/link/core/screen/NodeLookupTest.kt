package tech.atomiclabs.link.core.screen

import tech.atomiclabs.link.core.fakes.FakeClock
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import tech.atomiclabs.link.core.port.Bounds

class NodeLookupTest {
    @Test fun lookupCanResolveScreenIdsBeyondTheSearchOnlyVisitBudget() = runTest {
        val root = node(null)
        repeat(5001) { root.nodes += node(null) }
        val target = node("late visible node")
        root.nodes += target
        val data = snapshot(root)
        val walker = ScreenTreeWalker(FakeClock(testScheduler))
        val id = walker.read(data).nodes.single().nodeId
        assertSame(target, NodeLookup(walker).find(data, id)!!.node)
    }

    @Test fun screenAndSearchIdsResolveThroughTheSameFilteredPerWindowOrder() = runTest {
        val root = node(null)
        root.nodes += node("zero", bounds = Bounds(0, 0, 0, 0))
        val target = node("target")
        root.nodes += target
        val other = node("other", id = 88)
        val data = snapshot(root, other)
        val walker = ScreenTreeWalker(FakeClock(testScheduler))
        val lookup = NodeLookup(walker)
        for (result in walker.read(data).nodes) {
            assertEquals(result.properties.text, lookup.find(data, result.nodeId)!!.properties.text)
            assertEquals(result.nodeId, NodeSearch(walker).find(data, result.properties.text).nodes.single().nodeId)
        }
        assertSame(target, lookup.find(data, "w7:0")!!.node)
        assertNull(lookup.find(data, "w7:99"))
        assertNull(lookup.find(data, "invalid"))
    }
}
