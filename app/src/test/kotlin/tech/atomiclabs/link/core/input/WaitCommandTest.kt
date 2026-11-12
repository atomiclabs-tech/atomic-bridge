package tech.atomiclabs.link.core.input

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import tech.atomiclabs.link.core.command.CommandResult
import tech.atomiclabs.link.core.command.ErrorCode
import tech.atomiclabs.link.core.fakes.FakeClock
import tech.atomiclabs.link.core.fakes.FakeUiNode
import tech.atomiclabs.link.core.port.*
import tech.atomiclabs.link.core.screen.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class WaitCommandTest {
    @Test fun plainSleepUsesNoPreconditionsAndExplicitMsOverridesAQuery() = runTest {
        val f = InputFixture(testScheduler)
        f.tree.connection = PortResult.Success(false)
        assertEquals(0, f.send("/wait", "ms" to -1)["slept_ms"]!!.jsonPrimitive.long)
        assertEquals(400, f.send("/wait", "ms" to 400, "text" to "ignored")["slept_ms"]!!.jsonPrimitive.long)
        assertEquals(500, f.send("/wait", "timeout_ms" to 500)["slept_ms"]!!.jsonPrimitive.long)
        assertEquals(15_000, f.send("/wait", "ms" to 99_000)["slept_ms"]!!.jsonPrimitive.long)
        assertEquals(15_900, currentTime)
        assertEquals(0, f.gate.calls); assertEquals(0, f.tree.connectionReads)
    }

    @Test fun textAndSimpleOrExactClassQueriesReturnTheNodeWhenItAppears() = runTest {
        val f = InputFixture(testScheduler)
        backgroundScope.launch { delay(750); f.root.properties = f.root.properties.copy(text = "Now Appeared", className = "android.widget.Button") }
        val result = f.send("/wait", "text" to "APPEAR", "class_name" to "Button", "timeoutMs" to 1_000)
        assertTrue(result["found"]!!.jsonPrimitive.boolean)
        assertEquals(750, result["waited_ms"]!!.jsonPrimitive.long)
        assertEquals("w11:0", result["node"]!!.jsonObject["nodeId"]!!.jsonPrimitive.content)
        assertTrue(f.send("/wait", "className" to "android.widget.Button")["found"]!!.jsonPrimitive.boolean)
        assertEquals(2, f.gate.calls)
    }

    @Test fun timeoutHonorsAliasesZeroAndClampsAndReportsFoundFalse() = runTest {
        val f = InputFixture(testScheduler)
        for ((timeout, expected) in listOf(600 to 600, -1 to 0, 99_000 to 20_000)) {
            val result = f.send("/wait", "text" to "absent", "timeout_ms" to timeout)
            assertEquals("wait_timeout", result["code"]!!.jsonPrimitive.content)
            assertFalse(result["found"]!!.jsonPrimitive.boolean)
            assertEquals(expected.toLong(), result["waited_ms"]!!.jsonPrimitive.long)
        }
    }

    @Test fun timeoutIncludesAnInFlightTreeReadRatherThanWaitingForItToFinish() = runTest {
        val f = InputFixture(testScheduler)
        val slow = object : UiTreeSource by f.tree {
            override suspend fun windows(): PortResult<List<UiWindow>> = try {
                delay(2_000); f.tree.windows()
            } catch (cause: TimeoutCancellationException) {
                PortResult.Failure(PortFailure(FailureKind.TIMED_OUT, cause))
            }
        }
        val command = WaitCommand(WindowOrder(slow, f.logger), ScreenTreeWalker(FakeClock(testScheduler)), FakeClock(testScheduler))
        val result = command.execute(params("text" to "root", "timeoutMs" to 100)) as CommandResult.Failure
        assertEquals(ErrorCode.WAIT_TIMEOUT, result.code)
        assertEquals(100, currentTime)
        assertFalse(result.fields["found"]!!.jsonPrimitive.boolean)
    }

    @Test fun targetAfterSixThousandVisitedNodesIsFoundOnTheFirstPoll() = runTest {
        val f = InputFixture(testScheduler)
        var parent = f.root
        repeat(6000) {
            val child = FakeUiNode(UiNodeProperties(Bounds(0, 0, 10, 10), 11))
            parent.nodes += child
            parent = child
        }
        parent.nodes += FakeUiNode(UiNodeProperties(Bounds(0, 0, 10, 10), 11, text = "late target"))
        val result = f.send("/wait", "text" to "late target", "timeoutMs" to 500)
        assertEquals(JsonPrimitive(true), result["found"])
        assertEquals(JsonPrimitive(0), result["waited_ms"])
        assertEquals(JsonPrimitive(6002), result["visited"])
    }

    @Test fun compatibilityCharacterMatchesTextAndContentDescription() = runTest {
        val f = InputFixture(testScheduler)
        f.root.properties = f.root.properties.copy(text = "℃")
        val result = f.send("/wait", "text" to "°C", "timeoutMs" to 500)
        assertEquals(JsonPrimitive(true), result["found"])
        assertEquals(JsonPrimitive(1), result["visited"])
        f.root.properties = f.root.properties.copy(text = "", contentDescription = "℃")
        assertEquals(JsonPrimitive(true), f.send("/wait", "text" to "°C")["found"])
    }

    @Test fun nonBreakingWhitespaceIsCollapsedForTheQuery() = runTest {
        val f = InputFixture(testScheduler)
        f.root.properties = f.root.properties.copy(text = "Me\u00a0at the zoo")
        assertEquals(JsonPrimitive(true), f.send("/wait", "text" to "me at the zoo", "timeoutMs" to 500)["found"])
    }

    @Test fun emptyTextWithDismissContentDescriptionIsFoundWithoutMatchingHints() = runTest {
        val f = InputFixture(testScheduler)
        f.root.properties = f.root.properties.copy(text = "", contentDescription = "Dismiss", hintText = "Hint")
        assertEquals(JsonPrimitive(true), f.send("/wait", "text" to "dismiss")["found"])
        assertEquals(JsonPrimitive("wait_timeout"), f.send("/wait", "text" to "Hint", "timeoutMs" to 100)["code"])
    }

    @Test fun everyTruncatedPollStillReturnsWaitTimeoutWithLastPollVisits() = runTest {
        val f = InputFixture(testScheduler)
        repeat(20_000) { f.root.nodes += FakeUiNode(UiNodeProperties(Bounds(0, 0, 10, 10), 11, text = "other")) }
        val result = f.send("/wait", "text" to "absent", "timeoutMs" to 600)
        assertEquals(JsonPrimitive("wait_timeout"), result["code"])
        assertEquals(JsonPrimitive(false), result["found"])
        assertEquals(JsonPrimitive(true), result["truncated"])
        assertEquals(JsonPrimitive(20_000), result["visited"])
        assertEquals(JsonPrimitive(600), result["waited_ms"])
    }

    @Test fun timeoutAggregatesEarlierTruncationAndReportsTheLastCompletedPollCount() = runTest {
        val f = InputFixture(testScheduler)
        repeat(20_000) { f.root.nodes += FakeUiNode(UiNodeProperties(Bounds(0, 0, 10, 10), 11)) }
        backgroundScope.launch { FakeClock(testScheduler).sleep(250); f.root.nodes.clear() }
        val result = f.send("/wait", "text" to "absent", "timeoutMs" to 600)
        assertEquals(JsonPrimitive("wait_timeout"), result["code"])
        assertEquals(JsonPrimitive(true), result["truncated"])
        assertEquals(JsonPrimitive(1), result["visited"])
    }

    @Test fun nodeReadFailureDuringPollRemainsAnErrorWithItsCauseLogged() = runTest {
        val f = InputFixture(testScheduler)
        val broken = FakeUiNode(UiNodeProperties(Bounds(0, 0, 10, 10), 11))
        val cause = IllegalStateException("private-wait-node")
        broken.readFailure = PortFailure(FailureKind.PLATFORM, cause)
        f.root.nodes += broken
        assertEquals(JsonPrimitive("internal_error"), f.send("/wait", "text" to "absent")["code"])
        val error = f.logger.entries.single { it.level == "error" }
        assertTrue(generateSequence(error.cause) { it.cause }.any { it.stackTrace.contentEquals(cause.stackTrace) })
        assertFalse(error.cause!!.stackTraceToString().contains("private-wait-node"))
    }

    @Test fun completeThirtyNineNodeLoadingPageTimesOutWithoutTruncation() = runTest {
        val f = InputFixture(testScheduler)
        repeat(38) { f.root.nodes += loadingNode() }
        val result = f.send("/wait", "text" to "°", "timeoutMs" to 8_000)
        assertEquals(JsonPrimitive("wait_timeout"), result["code"])
        assertEquals(JsonPrimitive(false), result["truncated"])
        assertEquals(JsonPrimitive(39), result["visited"])
        assertEquals(8_000, currentTime)
    }

    @Test fun snapshotConsumingRemainingWaitTimeKeepsTheLastCompletedPollOutcome() = runTest {
        val f = InputFixture(testScheduler)
        repeat(38) { f.root.nodes += loadingNode() }
        var snapshots = 0
        val slow = object : UiTreeSource by f.tree {
            override suspend fun windows(): PortResult<List<UiWindow>> {
                if (++snapshots == 2) testScheduler.advanceTimeBy(7_750)
                return f.tree.windows()
            }
        }
        val clock = FakeClock(testScheduler)
        val result = WaitCommand(WindowOrder(slow, f.logger), ScreenTreeWalker(clock), clock)
            .execute(params("text" to "°", "timeoutMs" to 8_000)) as CommandResult.Failure
        assertEquals(ErrorCode.WAIT_TIMEOUT, result.code)
        assertEquals(JsonPrimitive(false), result.fields["truncated"])
        assertEquals(JsonPrimitive(39), result.fields["visited"])
        assertEquals(2, snapshots)
        assertEquals(8_000, currentTime)
    }

    @Test fun aRealTimeCutoffAtThirtyNineVisitsStillReportsTruncation() = runTest {
        val f = InputFixture(testScheduler)
        repeat(60) { index ->
            val raw = loadingNode()
            f.root.nodes += object : UiNode by raw {
                override suspend fun read(): PortResult<UiNodeProperties> {
                    if (index == 37) testScheduler.advanceTimeBy(8_000)
                    return raw.read()
                }
            }
        }
        val result = f.send("/wait", "text" to "°", "timeoutMs" to 8_000)
        assertEquals(JsonPrimitive("wait_timeout"), result["code"])
        assertEquals(JsonPrimitive(true), result["truncated"])
        assertEquals(JsonPrimitive(39), result["visited"])
        assertEquals(8_000, currentTime)
    }

    @Test fun earlierBudgetCutoffAndLastCompleteThirtyNineNodePollRemainDistinct() = runTest {
        val f = InputFixture(testScheduler)
        repeat(20_000) { f.root.nodes += loadingNode() }
        backgroundScope.launch {
            FakeClock(testScheduler).sleep(250)
            f.root.nodes.clear()
            repeat(38) { f.root.nodes += loadingNode() }
        }
        val result = f.send("/wait", "text" to "°", "timeoutMs" to 600)
        assertEquals(JsonPrimitive("wait_timeout"), result["code"])
        assertEquals(JsonPrimitive(true), result["truncated"])
        assertEquals(JsonPrimitive(39), result["visited"])
    }

    private fun loadingNode() = FakeUiNode(UiNodeProperties(Bounds(0, 0, 10, 10), 11, text = "loading"))
}
