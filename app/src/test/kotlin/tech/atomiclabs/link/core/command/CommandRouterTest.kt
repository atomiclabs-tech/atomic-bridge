package tech.atomiclabs.link.core.command

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import tech.atomiclabs.link.core.fakes.*
import tech.atomiclabs.link.core.port.*
import tech.atomiclabs.link.core.protocol.*

@OptIn(ExperimentalCoroutinesApi::class)
class CommandRouterTest {
    private val logger = FakeLogger()
    private val tree = FakeUiTree()
    private val gate = FakeDisplayGate()
    private val requestId = "11111111-2222-3333-4444-555555555555"
    private val ok = CommandResult.Success()

    private fun router(vararg registered: Route): CommandRouter = CommandRouter(
        listOf(object : RouteGroup { override val routes = registered.toList() }), tree, gate, logger,
    )

    private fun command(
        id: String = requestId,
        path: String = "/example",
        method: String = "POST",
        body: JsonObject = JsonObject(emptyMap()),
    ) = BridgeCommand(id, method, path, body = body)

    private fun failure(response: BridgeResponse, code: ErrorCode) {
        assertEquals(200, response.status)
        assertFalse(response.result["success"]!!.jsonPrimitive.boolean)
        assertEquals(code.wireValue, response.result["code"]!!.jsonPrimitive.content)
        assertTrue(response.result["error"]!!.jsonPrimitive.content.isNotBlank())
    }

    @Test fun unknownPathAndWrongMethodReturnTheContractFailureBeforeAnyPrecondition() = runTest {
        val router = router(Route("POST", "/example", { RouteNeeds(true, true) }) { ok })
        failure(router.dispatch(command(path = "/missing")), ErrorCode.UNKNOWN_COMMAND)
        failure(router.dispatch(command(id = "second", method = "GET")), ErrorCode.UNKNOWN_COMMAND)
        assertEquals(0, tree.connectionReads)
        assertEquals(0, gate.calls)
    }

    @Test fun disconnectedAccessibilityStopsBeforeDisplayAndHandler() = runTest {
        tree.connection = PortResult.Success(false)
        var calls = 0
        val router = router(Route("POST", "/example", { RouteNeeds(true, true) }) { calls++; ok })
        failure(router.dispatch(command()), ErrorCode.ACCESSIBILITY_DISABLED)
        assertEquals(1, tree.connectionReads)
        assertEquals(0, gate.calls)
        assertEquals(0, calls)
    }

    @Test fun accessibilityReadFailureIsLoggedAndCannotBecomeDisconnectedOrSuccess() = runTest {
        tree.connection = PortResult.Failure(PortFailure(FailureKind.PLATFORM, IllegalStateException("private-read")))
        val router = router(Route("POST", "/example", { RouteNeeds(true, true) }) { fail("handler ran"); ok })
        failure(router.dispatch(command()), ErrorCode.INTERNAL_ERROR)
        assertEquals(0, gate.calls)
        assertEquals("accessibilityState", logger.entries.single().operation)
        assertFalse(logger.entries.single().cause!!.stackTraceToString().contains("private-read"))
    }

    @Test fun displayGateRunsBeforeHandlerAndBothFailureCodesArePreserved() = runTest {
        for (code in listOf(ErrorCode.WAKE_FAILED, ErrorCode.KEYGUARD_BLOCKED)) {
            gate.result = DisplayGateResult.Failed(CommandResult.Failure(code, "Display preparation failed."))
            val router = router(Route("POST", "/example", { RouteNeeds(display = true) }) { fail("handler ran"); ok })
            failure(router.dispatch(command()), code)
        }
        val order = mutableListOf<String>()
        gate.result = DisplayGateResult.Ready
        gate.beforePrepare = { order += "display" }
        val router = router(Route("POST", "/example", { RouteNeeds(display = true) }) { order += "handler"; ok })
        assertTrue(router.dispatch(command()).result["success"]!!.jsonPrimitive.boolean)
        assertEquals(listOf("display", "handler"), order)
    }

    @Test fun handlerThrowableIsContainedLoggedWithStackAndCannotLeakItsMessage() = runTest {
        val thrown = AssertionError("private-handler-data", IllegalStateException("private-nested-data"))
        val router = router(Route("POST", "/example") { throw thrown })
        failure(router.dispatch(command()), ErrorCode.INTERNAL_ERROR)
        val entry = logger.entries.single()
        assertEquals("error", entry.level)
        assertEquals("CommandRouter", entry.component)
        assertEquals("request_id=$requestId path=/example", entry.context)
        val diagnostic = entry.cause!!
        // Coroutine recovery can add an async boundary while retaining the original throwable as its cause.
        assertTrue(generateSequence(diagnostic) { it.cause }.any { it.stackTrace.contentEquals(thrown.stackTrace) })
        assertTrue(diagnostic.stackTraceToString().contains("AssertionError"))
        assertFalse(diagnostic.stackTraceToString().contains("private-"))
    }

    @Test fun deadlineCancelsAHandlerAt25SecondsAndTheTimeoutIsReplayed() = runTest {
        var calls = 0
        var finished = false
        val router = router(Route("POST", "/example") {
            calls++
            try { delay(30_000); ok } finally { finished = true }
        })
        val response = async { router.dispatch(command()) }
        runCurrent()
        advanceTimeBy(24_999)
        assertFalse(response.isCompleted)
        advanceTimeBy(1); runCurrent()
        failure(response.await(), ErrorCode.TIMEOUT)
        assertTrue(finished)
        assertEquals(25_000, currentTime)
        assertSame(response.await(), router.dispatch(command()))
        assertEquals(1, calls)
        assertEquals("deadline", logger.entries.single().operation)
    }

    @Test fun repeatedIdDuringExecutionAndAfterCompletionRunsTheFirstHandlerOnce() = runTest {
        var calls = 0
        val release = CompletableDeferred<Unit>()
        val router = router(Route("POST", "/example") { calls++; release.await(); ok })
        val first = async { router.dispatch(command()) }
        runCurrent()
        val duplicate = async { router.dispatch(command(path = "/different", body = buildJsonObject { put("text", "different") })) }
        runCurrent()
        assertEquals(1, calls)
        assertFalse(duplicate.isCompleted)
        release.complete(Unit)
        assertSame(first.await(), duplicate.await())
        assertSame(first.await(), router.dispatch(command()))
        assertEquals(1, calls)
    }

    @Test fun firstFailureIsReplayedWithoutCallingTheHandlerAgain() = runTest {
        var calls = 0
        val router = router(Route("POST", "/example") { calls++; throw IllegalStateException("failure") })
        val first = router.dispatch(command())
        assertSame(first, router.dispatch(command()))
        failure(first, ErrorCode.INTERNAL_ERROR)
        assertEquals(1, calls)
        assertEquals(1, logger.entries.size)
    }

    @Test fun displayCommandsRunSeriallyInArrivalOrderWhileOrdinaryWorkCanProgress() = runTest {
        val events = mutableListOf<String>()
        val router = router(
            Route("POST", "/display", { RouteNeeds(display = true) }) { body ->
                val label = body.requiredString("label")
                events += "$label-start"; delay(100); events += "$label-end"; ok
            },
            Route("POST", "/ordinary") { events += "ordinary-start"; delay(10); events += "ordinary-end"; ok },
        )
        fun display(id: String) = command(id, "/display", body = buildJsonObject { put("label", id) })
        val first = async { router.dispatch(display("first")) }
        val second = async { router.dispatch(display("second")) }
        val third = async { router.dispatch(display("third")) }
        val ordinary = async { router.dispatch(command("fourth", "/ordinary")) }
        runCurrent()
        assertEquals(listOf("first-start", "ordinary-start"), events)
        advanceUntilIdle()
        assertEquals(listOf("first-start", "ordinary-start", "ordinary-end", "first-end", "second-start",
            "second-end", "third-start", "third-end"), events)
        listOf(first, second, third, ordinary).forEach { assertEquals(200, it.await().status) }
        assertEquals(300, currentTime)
    }

    @Test fun suspendedAccessibilityCheckCannotLetALaterDisplayRequestOvertakeIt() = runTest {
        val releaseAccessibility = CompletableDeferred<Unit>()
        var reads = 0
        tree.beforeConnectionRead = { if (++reads == 1) releaseAccessibility.await() }
        val order = mutableListOf<String>()
        val router = router(Route("POST", "/example", { RouteNeeds(true, true) }) { body ->
            order += body.requiredString("label"); ok
        })
        val first = async { router.dispatch(command("one", body = buildJsonObject { put("label", "first") })) }
        runCurrent()
        val second = async { router.dispatch(command("two", body = buildJsonObject { put("label", "second") })) }
        runCurrent()
        assertTrue(order.isEmpty())
        assertEquals(1, reads)
        releaseAccessibility.complete(Unit)
        first.await(); second.await()
        assertEquals(listOf("first", "second"), order)
    }

    @Test fun nonDisplayRoutesRunConcurrentlyAndOneFailureDoesNotCancelTheOthers() = runTest {
        val started = mutableListOf<Int>()
        val router = router(Route("POST", "/example") { body ->
            val number = body.requiredInt("number")
            started += number; delay(100)
            if (number == 1) throw IllegalStateException("one failed")
            ok
        })
        val first = async { router.dispatch(command("one", body = buildJsonObject { put("number", 1) })) }
        val second = async { router.dispatch(command("two", body = buildJsonObject { put("number", 2) })) }
        runCurrent()
        assertEquals(listOf(1, 2), started)
        assertEquals(0, gate.calls)
        advanceUntilIdle()
        failure(first.await(), ErrorCode.INTERNAL_ERROR)
        assertTrue(second.await().result["success"]!!.jsonPrimitive.boolean)
        assertEquals(100, currentTime)
    }

    @Test fun requirementsCanDependOnParsedBodyAndInvalidParametersReturnBadRequest() = runTest {
        val router = router(Route("POST", "/example", { body ->
            val needs = body.requiredBoolean("display")
            RouteNeeds(needs, needs)
        }) { ok })
        tree.connection = PortResult.Success(false)
        assertTrue(router.dispatch(command("one", body = buildJsonObject { put("display", "false") }))
            .result["success"]!!.jsonPrimitive.boolean)
        assertEquals(0, tree.connectionReads)
        failure(router.dispatch(command("two", body = buildJsonObject { put("display", "true") })),
            ErrorCode.ACCESSIBILITY_DISABLED)
        val invalid = router.dispatch(command("three"))
        failure(invalid, ErrorCode.BAD_REQUEST)
        assertTrue(invalid.result["error"]!!.jsonPrimitive.content.contains("display"))
    }

    @Test fun queueAndDisplayPreparationAreIncludedInTheDeadline() = runTest {
        gate.beforePrepare = { delay(30_000) }
        var calls = 0
        val router = router(Route("POST", "/example", { RouteNeeds(display = true) }) { calls++; ok })
        val first = async { router.dispatch(command("first")) }
        val second = async { router.dispatch(command("second")) }
        advanceUntilIdle()
        failure(first.await(), ErrorCode.TIMEOUT)
        failure(second.await(), ErrorCode.TIMEOUT)
        assertEquals(25_000, currentTime)
        assertEquals(0, calls)
    }

    @Test fun cancelledOwnerRecordsFailureAndCannotRepeatAnAlreadyStartedAction() = runTest {
        var calls = 0
        val router = router(Route("POST", "/example") { calls++; awaitCancellation() })
        val owner = launch { router.dispatch(command()) }
        runCurrent()
        owner.cancelAndJoin()
        failure(router.dispatch(command()), ErrorCode.INTERNAL_ERROR)
        assertEquals(1, calls)
        assertEquals(1, logger.entries.size)
    }

    @Test fun invalidHandlerResultsBecomeContractFailuresRatherThanPartialSuccess() = runTest {
        val router = router(Route("POST", "/example") {
            CommandResult.Success(buildJsonObject { put("success", false) })
        })
        failure(router.dispatch(command()), ErrorCode.INTERNAL_ERROR)
        assertEquals(1, logger.entries.size)
    }

    @Test fun duplicateRouteRegistrationsAreConfigurationErrors() {
        assertThrows(IllegalArgumentException::class.java) {
            router(Route("POST", "/example") { ok }, Route("POST", "/example") { ok })
        }
    }
}
