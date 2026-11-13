package tech.atomiclabs.link.core.screen

import tech.atomiclabs.link.core.fakes.FakeClock
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import tech.atomiclabs.link.core.port.*

class ScreenTreeWalkerTest {
    @Test fun sixHundredNodesYield512AndTruncatedWithPerWindowEmissionIds() = runTest {
        val root = node(null)
        repeat(600) { root.nodes += node("item$it") }
        val result = ScreenTreeWalker(FakeClock(testScheduler)).read(snapshot(root))
        assertEquals(512, result.nodes.size)
        assertTrue(result.truncated)
        assertEquals("w7:0", result.nodes.first().nodeId)
        assertEquals("w7:511", result.nodes.last().nodeId)
        val second = node(id = 8)
        assertEquals(listOf("w7:0", "w8:0"), ScreenTreeWalker(FakeClock(testScheduler)).read(snapshot(node(), second)).nodes.map { it.nodeId })
    }

    @Test fun containerInspectionIsOptInAndKeepsTheSameBoundedTraversal() = runTest {
        val root = node(null)
        root.properties = root.properties.copy(viewId = "container-marker")
        root.nodes += node("child")
        val walker = ScreenTreeWalker(FakeClock(testScheduler))
        val ordinary = mutableListOf<UiNodeProperties>()
        walker.visit(snapshot(root), MAX_SEARCH_VISITS) { ordinary += it.properties; true }
        assertEquals(1, ordinary.size)
        val all = mutableListOf<UiNodeProperties>()
        val outcome = walker.visit(snapshot(root), 1, includeContainers = true) { all += it.properties; true }
        assertEquals(VisitOutcome(1, true), outcome)
        assertEquals("container-marker", all.single().viewId)
    }

    @Test fun filteringDoesNotHideDescendantsAndRootBoundsAreTheUnionOfRealRoots() = runTest {
        val hidden = node("zero", bounds = Bounds(0, 0, 0, 0))
        hidden.nodes += node("child")
        val other = node(id = 8, bounds = Bounds(-10, -20, 100, 200))
        val result = ScreenTreeWalker(FakeClock(testScheduler)).read(snapshot(hidden, other))
        assertEquals(listOf("child", "visible"), result.nodes.map { it.properties.text })
        assertEquals(Bounds(-10, -20, 100, 200), result.rootBounds)
    }

    @Test fun childReadFailureRemainsAnErrorRatherThanPartialSuccess() = runTest {
        val root = node()
        val failure = PortFailure(FailureKind.PLATFORM, IllegalStateException("fixture"))
        root.childFailure = failure
        try { ScreenTreeWalker(FakeClock(testScheduler)).read(snapshot(root)); fail("Children failure was swallowed") }
        catch (cause: ScreenReadException) { assertSame(failure, cause.failure) }
    }

    @Test fun traversalStopsAtTwentyThousandVisitedNodesAndReportsTruncation() = runTest {
        val root = node(null)
        repeat(20_000) { root.nodes += node("item") }
        var accepted = 0
        val result = ScreenTreeWalker(FakeClock(testScheduler)).visit(snapshot(root), MAX_SEARCH_VISITS) {
            accepted++; true
        }
        assertEquals(20_000, result.visited)
        assertTrue(result.truncated)
        assertEquals(19_999, accepted)
    }

    @Test fun exactCompletionAndConsumerStopsAreNotBudgetTruncationAcrossWindows() = runTest {
        val walker = ScreenTreeWalker(FakeClock(testScheduler))
        val data = snapshot(node("one"), node("two", id = 8))
        assertEquals(VisitOutcome(2, false), walker.visit(data, 2) { true })
        assertEquals(VisitOutcome(1, true), walker.visit(data, 1) { true })
        assertEquals(VisitOutcome(1, false), walker.visit(data, 1) { false })
        assertEquals(VisitOutcome(0, false), walker.visit(snapshot(), 0) { true })
    }

    @Test fun defaultSearchTimeBudgetChecksTheInjectedClockBetweenVisitedNodes() = runTest {
        val root = node("root")
        root.nodes += node("unvisited")
        val clock = FakeClock(testScheduler)
        val result = ScreenTreeWalker(clock).visit(snapshot(root), MAX_SEARCH_VISITS) {
            testScheduler.advanceTimeBy(MAX_SEARCH_MS)
            true
        }
        assertEquals(VisitOutcome(1, true), result)
        assertEquals(MAX_SEARCH_MS, clock.monotonicMs())
    }

    @Test fun explicitEarlierDeadlineAlsoStopsBeforeReadingTheNextNode() = runTest {
        val root = node("root")
        val broken = node("must not read")
        broken.readFailure = PortFailure(FailureKind.PLATFORM, IllegalStateException("must not read"))
        root.nodes += broken
        val result = ScreenTreeWalker(FakeClock(testScheduler)).visit(snapshot(root), MAX_SEARCH_VISITS, 100) {
            testScheduler.advanceTimeBy(100)
            true
        }
        assertEquals(VisitOutcome(1, true), result)
    }

    @Test fun readCrossingTheTimeDeadlineStopsBeforeTheNodeCanBeAccepted() = runTest {
        val root = node(null)
        val target = node("target")
        root.nodes += object : UiNode by target {
            override suspend fun read(): PortResult<UiNodeProperties> {
                testScheduler.advanceTimeBy(MAX_SEARCH_MS)
                return target.read()
            }
        }
        var accepted = 0
        val result = ScreenTreeWalker(FakeClock(testScheduler)).visit(snapshot(root), MAX_SEARCH_VISITS) {
            accepted++; false
        }
        assertEquals(0, accepted)
        assertEquals(VisitOutcome(2, true), result)
    }

    @Test fun nodeReadFailureInsideTheWalkKeepsItsTypedCause() = runTest {
        val root = node("root")
        val broken = node("broken")
        val original = IllegalStateException("private-node-read")
        val failure = PortFailure(FailureKind.PLATFORM, original)
        broken.readFailure = failure
        root.nodes += broken
        try {
            ScreenTreeWalker(FakeClock(testScheduler)).visit(snapshot(root), MAX_SEARCH_VISITS) { true }
            fail("Node failure became partial success")
        } catch (cause: ScreenReadException) {
            assertSame(failure, cause.failure)
            assertSame(original, cause.cause)
        }
    }
}
