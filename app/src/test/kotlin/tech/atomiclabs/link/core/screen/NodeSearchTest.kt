package tech.atomiclabs.link.core.screen

import tech.atomiclabs.link.core.fakes.FakeClock
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import tech.atomiclabs.link.core.port.Bounds
import tech.atomiclabs.link.core.port.FailureKind
import tech.atomiclabs.link.core.port.PortFailure

class NodeSearchTest {
    @Test fun nonmatchingNodesDoNotConsumeLimitAndSearchStopsBeforeLaterFailure() = runTest {
        val root = node(null)
        repeat(30) { root.nodes += node("other") }
        repeat(4) {
            val matching = node("match $it")
            if (it >= 2) matching.readFailure = PortFailure(FailureKind.PLATFORM, IllegalStateException("must not read"))
            root.nodes += matching
        }
        val search = NodeSearch(ScreenTreeWalker(FakeClock(testScheduler)))
        val result = search.find(snapshot(root), text = "MATCH", limit = 2)
        val results = result.nodes
        assertEquals(listOf("w7:30", "w7:31"), results.map { it.nodeId })
        assertEquals(2, results.size)
        assertFalse(result.outcome.truncated)
    }

    @Test fun visitBudgetIsTwentyThousandAndFiltersCombineWithoutEmittingZeroRectangles() = runTest {
        val root = node(null)
        repeat(19_999) { root.nodes += node("other") }
        root.nodes += node("late match")
        val cutoff = NodeSearch(ScreenTreeWalker(FakeClock(testScheduler))).find(snapshot(root), text = "late")
        assertTrue(cutoff.nodes.isEmpty())
        assertEquals(VisitOutcome(20_000, true), cutoff.outcome)
        val target = node("target").also { it.properties = it.properties.copy(className = "Button", clickable = true) }
        val zero = node("target", bounds = Bounds(0, 0, 0, 0))
        val results = NodeSearch(ScreenTreeWalker(FakeClock(testScheduler))).find(snapshot(zero, target), "target", "Button", true)
        assertEquals(1, results.nodes.size)
    }

    @Test fun targetBeyondSixThousandDeepNodesIsFoundWithoutUsingTheCallStack() = runTest {
        val root = node(null)
        var parent = root
        repeat(6000) {
            val child = node(null)
            parent.nodes += child
            parent = child
        }
        parent.nodes += node("late target")
        val result = NodeSearch(ScreenTreeWalker(FakeClock(testScheduler))).find(snapshot(root), "late target")
        assertEquals(1, result.nodes.size)
        assertEquals("late target", result.nodes.single().properties.text)
        assertEquals(6002, result.outcome.visited)
        assertFalse(result.outcome.truncated)
    }

    @Test fun compatibilityCharacterIsMatchedAfterNormalization() = runTest {
        val search = NodeSearch(ScreenTreeWalker(FakeClock(testScheduler)))
        val result = search.find(snapshot(node("℃")), "°C")
        assertEquals(1, result.nodes.size)
        assertEquals("℃", result.nodes.single().properties.text)
    }

    @Test fun nonBreakingWhitespaceIsCollapsedForTheQuery() = runTest {
        val search = NodeSearch(ScreenTreeWalker(FakeClock(testScheduler)))
        assertEquals(1, search.find(snapshot(node("Me\u00a0at the zoo")), "me at the zoo").nodes.size)
    }

    @Test fun substringAfterNonBreakingSpaceAlreadyMatches() = runTest {
        val search = NodeSearch(ScreenTreeWalker(FakeClock(testScheduler)))
        assertEquals(1, search.find(snapshot(node("Me\u00a0at the zoo")), "at the zoo").nodes.size)
    }

    @Test fun emptyTextWithDismissContentDescriptionIsMatchedButHintsAreNot() = runTest {
        val root = node("").also { it.properties = it.properties.copy(contentDescription = "Dismiss", hintText = "Hint") }
        val search = NodeSearch(ScreenTreeWalker(FakeClock(testScheduler)))
        assertEquals("Dismiss", search.find(snapshot(root), "dismiss").nodes.single().properties.contentDescription)
        assertTrue(search.find(snapshot(root), "hint").nodes.isEmpty())
    }

    @Test fun normalizedEmptyQueryDoesNotTurnIntoAnUnfilteredSearch() = runTest {
        val search = NodeSearch(ScreenTreeWalker(FakeClock(testScheduler)))
        assertTrue(search.find(snapshot(node("visible")), "\u00a0\u2007").nodes.isEmpty())
    }
}
