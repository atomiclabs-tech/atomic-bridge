package tech.atomiclabs.link.core.unlock

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import tech.atomiclabs.link.core.command.*
import tech.atomiclabs.link.core.events.EventBuffer
import tech.atomiclabs.link.core.fakes.*
import tech.atomiclabs.link.core.input.NodeClick
import tech.atomiclabs.link.core.port.*
import tech.atomiclabs.link.core.protocol.BridgeCommand
import tech.atomiclabs.link.core.screen.*

@OptIn(ExperimentalCoroutinesApi::class)
class UnlockRoutesTest {
    private class Fixture(scheduler: TestCoroutineScheduler) {
        val clock = FakeClock(scheduler)
        val logger = FakeLogger()
        val tree = FakeUiTree()
        val gate = FakeDisplayGate().apply {
            result = DisplayGateResult.Failed(CommandResult.Failure(ErrorCode.KEYGUARD_BLOCKED, "A secure display is gated."))
        }
        var layout = PinPadFixtures.oneUi()
        var showing = true
        var unlockAt: Long? = null
        var keyguardFailure: PortFailure? = null
        var failKeyguardAfter: Int? = null
        var keyguardReads = 0
        var keyguardSample: () -> KeyguardSnapshot = {
            KeyguardSnapshot(showing && (unlockAt == null || clock.monotonicMs() < unlockAt!!), true)
        }
        var wakeFailure: PortFailure? = null
        var interactiveFailure: PortFailure? = null
        var interactive = true
        var interactiveAt = 0L
        val interactiveTimes = mutableListOf<Long>()
        var wakes = 0
        var snapshots = 0
        val snapshotTimes = mutableListOf<Long>()
        var beforeSnapshot: () -> Unit = {}
        var metricsReads = 0
        var metricsFailure: PortFailure? = null
        var gestureFailure: PortFailure? = null
        val strokes = mutableListOf<List<GestureStroke>>()
        val gestureTimes = mutableListOf<Long>()
        var afterSwipe: () -> Unit = {}
        val keyguard = KeyguardState {
            keyguardReads++
            val failure = keyguardFailure
            if (failure != null && (failKeyguardAfter == null || keyguardReads > failKeyguardAfter!!)) PortResult.Failure(failure)
            else PortResult.Success(keyguardSample())
        }
        val power = object : PowerState {
            override suspend fun wakeWithBrightLock(durationMs: Long): PortResult<Unit> {
                assertEquals(10_000, durationMs)
                wakes++
                return wakeFailure?.let { PortResult.Failure(it) } ?: PortResult.Success(Unit)
            }
            override suspend fun isInteractive(): PortResult<Boolean> {
                interactiveTimes += clock.monotonicMs()
                return interactiveFailure?.let { PortResult.Failure(it) }
                    ?: PortResult.Success(interactive && clock.monotonicMs() >= interactiveAt)
            }
        }
        val gestures = GestureInput {
            strokes += it
            gestureTimes += clock.monotonicMs()
            val failure = gestureFailure
            if (failure == null) { afterSwipe(); PortResult.Success(Unit) } else PortResult.Failure(failure)
        }
        private val source = object : UiTreeSource by tree {
            override suspend fun windows(): PortResult<List<UiWindow>> {
                snapshots++
                snapshotTimes += clock.monotonicMs()
                beforeSnapshot()
                return tree.windows()
            }
        }
        val group = UnlockRoutes(keyguard, power, gestures, ScreenMetrics {
            metricsReads++
            metricsFailure?.let { PortResult.Failure(it) } ?: PortResult.Success(DisplayMetrics(1080, 2400, 3f))
        }, WindowOrder(source, logger), ScreenTreeWalker(clock), clock, logger, NodeClick(gestures), EventBuffer(clock))
        val router = CommandRouter(listOf(group), source, gate, logger)
        init { show(layout) }
        fun show(pad: PinPadFixtures.Pad) { layout = pad; tree.windowResult = pad.tree().windowResult }
        suspend fun send(pin: String?, id: String = "unlock-request") = sendBody(buildJsonObject {
            if (pin != null) put("pin", pin)
        }, id)
        suspend fun sendBody(body: JsonObject, id: String = "unlock-request") =
            router.dispatch(BridgeCommand(id, "POST", "/unlock", body = body))
    }

    private fun syntheticPin(length: Int = 6) = buildString {
        repeat(length) { append(('0'.code + kotlin.random.Random.nextInt(10)).toChar()) }
    }

    private fun assertPrivate(f: Fixture, pin: String?, result: JsonObject) {
        val output = listOf(result.toString()) + f.logger.entries.map { it.component + it.operation + it.context }
        assertTrue("Unlock output must contain no entered digit.", output.none { text -> text.any { it in '0'..'9' } })
        val messages = f.logger.entries.flatMap { entry ->
            generateSequence(entry.cause) { it.cause }.mapNotNull { it.message }.toList()
        }
        val labels = listOf("OK", "Delete", "Cancel", "Enter", "private-node-label")
        assertTrue("Unlock output must contain no key label.", (output + messages).none { text -> labels.any { text.contains(it) } })
        if (pin != null) {
            assertTrue("Diagnostics must redact the supplied value.", messages.none { it.contains(pin) })
            assertTrue("Diagnostic stack traces must redact the supplied value.",
                f.logger.entries.none { it.cause?.stackTraceToString()?.contains(pin) == true })
        }
    }

    private fun assertFailure(f: Fixture, pin: String?, result: JsonObject, stage: String, attempted: Boolean,
                              code: String = "keyguard_blocked") {
        assertPrivate(f, pin, result)
        assertEquals(JsonPrimitive(false), result["success"])
        assertEquals(JsonPrimitive(code), result["code"])
        assertEquals(JsonPrimitive(stage), result["stage"])
        assertEquals(JsonPrimitive(attempted), result["pin_attempt_used"])
        assertTrue("A failure must not claim an unlock.", !result.containsKey("unlocked") && !result.containsKey("already_unlocked"))
        val message = when (stage) {
            "keyguard" -> "The keyguard state could not be read."
            "wake" -> "The display did not turn on."
            else -> "The phone was not unlocked."
        }
        assertEquals(JsonPrimitive(message), result["error"])
        val log = f.logger.entries.last()
        assertEquals("UnlockRoutes", log.component)
        assertEquals("unlock", log.operation)
        assertEquals("state=$code stage=$stage", log.context)
        assertPrivate(f, pin, result)
    }

    private fun assertSequence(layout: PinPadFixtures.Pad, pin: String, confirm: Boolean) {
        assertEquals(pin.length + if (confirm) 1 else 0, layout.clicks.size)
        assertTrue("Each requested key must be clicked in order.",
            layout.clicks.take(pin.length).zip(pin.toList()).all { (node, digit) -> node === layout.digits.getValue(digit) })
        if (confirm) assertTrue("The final click must be the confirm key.", layout.clicks.last() === layout.confirm)
    }

    @Test fun alreadyUnlockedChecksAccessibilityWithoutUsingTheDisplayGateOrAnyInput() = runTest {
        val f = Fixture(testScheduler).apply { showing = false }
        val pin = syntheticPin()
        val result = f.send(pin).result
        assertPrivate(f, pin, result)
        assertEquals(buildJsonObject { put("unlocked", true); put("already_unlocked", true); put("success", true) }, result)
        assertEquals(1, f.tree.connectionReads)
        assertEquals(0, f.gate.calls)
        assertEquals(1, f.keyguardReads)
        assertEquals(0, f.wakes); assertEquals(0, f.snapshots)
        assertTrue(f.strokes.isEmpty() && f.layout.clicks.isEmpty())
        assertEquals(RouteNeeds(accessibility = true, display = false, exclusive = true), f.group.routes.single().needs(BodyParams(JsonObject(emptyMap()))))
        assertPrivate(f, pin, result)
    }

    @Test fun oneUiCompletePadEntersOneSequenceConfirmsAndPollsWithoutSwiping() = runTest {
        val f = Fixture(testScheduler).apply { unlockAt = 1_500; interactiveAt = 500 }
        val pin = syntheticPin(10)
        val result = f.send(pin).result
        assertPrivate(f, pin, result)
        assertEquals(buildJsonObject { put("unlocked", true); put("success", true) }, result)
        assertSequence(f.layout, pin, confirm = true)
        assertTrue(f.strokes.isEmpty())
        assertEquals(1, f.snapshots)
        assertEquals(listOf(0L, 250L, 500L), f.interactiveTimes)
        assertEquals(0, f.gate.calls)
        assertPrivate(f, pin, result)
    }

    @Test fun pixelEnterImageButtonIsConfirmed() = runTest {
        val f = Fixture(testScheduler).apply { show(PinPadFixtures.pixel()); unlockAt = 1_000 }
        val pin = syntheticPin()
        val result = f.send(pin).result
        assertPrivate(f, pin, result)
        assertEquals(JsonPrimitive(true), result["unlocked"])
        assertSequence(f.layout, pin, confirm = true)
        assertPrivate(f, pin, result)
    }

    @Test fun pixelWithoutConfirmSucceedsOnlyWhenTheKeyguardDisappears() = runTest {
        val f = Fixture(testScheduler).apply { show(PinPadFixtures.pixel(confirm = false)); unlockAt = 1_000 }
        val pin = syntheticPin()
        val result = f.send(pin).result
        assertPrivate(f, pin, result)
        assertEquals(JsonPrimitive(true), result["unlocked"])
        assertSequence(f.layout, pin, confirm = false)
        assertPrivate(f, pin, result)
    }

    @Test fun r3NeverVisiblePadStopsAfterTwoBoundedRevealsWithoutTyping() = runTest {
        val f = Fixture(testScheduler).apply { show(PinPadFixtures.oneUi(missing = '7')) }
        val pin = syntheticPin()
        val result = f.send(pin).result
        assertFailure(f, pin, result, "pin_pad_not_found", attempted = false)
        assertEquals(2, f.strokes.size)
        for (stroke in f.strokes) {
            assertEquals(listOf(GestureStroke(listOf(Point(540f, 1920f), Point(540f, 600f)), 250)), stroke)
        }
        assertTrue(f.layout.clicks.isEmpty())
        assertEquals(listOf(500L, 2_500L), f.gestureTimes)
        assertEquals(4_500L, currentTime)
        assertTrue("Every absent-pad poll must use the virtual cadence.",
            (500L..4_500L step 250).all { it in f.snapshotTimes })
    }

    @Test fun r1DelayedPadWaitsForInteractiveKeyguardSettlingAndAFullPadPoll() = runTest {
        val f = Fixture(testScheduler).apply {
            show(PinPadFixtures.oneUi(missing = '7'))
            interactiveAt = 500
            keyguardSample = {
                val now = clock.monotonicMs()
                KeyguardSnapshot((now < 250 || now >= 750) && now < 4_500, true)
            }
        }
        val incomplete = f.layout
        val complete = PinPadFixtures.pixel(confirm = false)
        f.beforeSnapshot = {
            val firstReveal = f.gestureTimes.firstOrNull()
            if (firstReveal != null && f.clock.monotonicMs() - firstReveal >= 1_500) f.show(complete)
        }
        val pin = syntheticPin()
        val first = f.send(pin)
        assertPrivate(f, pin, first.result)
        assertTrue("A delayed full pad must unlock.", first.result["unlocked"] == JsonPrimitive(true))
        assertEquals(listOf(1_250L), f.gestureTimes)
        assertTrue("The delayed pad must be observed by bounded polls.",
            (1_500L..2_750L step 250).all { it in f.snapshotTimes })
        assertTrue(incomplete.clicks.isEmpty())
        assertSequence(complete, pin, confirm = false)
        assertSame(first, f.send(pin))
        assertEquals(1, f.strokes.size)
        assertSequence(complete, pin, confirm = false)
    }

    @Test fun r2PadVisibleOnlyAfterSecondRevealStillUsesOneEntrySequence() = runTest {
        val f = Fixture(testScheduler).apply { show(PinPadFixtures.oneUi(missing = '7')); unlockAt = 4_000 }
        val incomplete = f.layout
        val complete = PinPadFixtures.pixel(confirm = false)
        f.afterSwipe = {
            assertTrue("No entry may precede the complete pad.", incomplete.clicks.isEmpty())
            if (f.strokes.size == 2) f.show(complete)
        }
        val pin = syntheticPin()
        val result = f.send(pin).result
        assertPrivate(f, pin, result)
        assertTrue("A second reveal must be allowed before entry.", result["unlocked"] == JsonPrimitive(true))
        assertEquals(listOf(500L, 2_500L), f.gestureTimes)
        assertTrue("The first reveal must receive its full polling budget.",
            (500L..2_500L step 250).all { it in f.snapshotTimes })
        assertTrue(incomplete.clicks.isEmpty())
        assertSequence(complete, pin, confirm = false)
    }

    @Test fun revealingThePadTakesOneSwipeThenUsesTheNewSnapshot() = runTest {
        val f = Fixture(testScheduler).apply { show(PinPadFixtures.oneUi(missing = '7')); unlockAt = 2_000 }
        val complete = PinPadFixtures.pixel()
        f.afterSwipe = { f.show(complete) }
        val pin = syntheticPin()
        val result = f.send(pin).result
        assertPrivate(f, pin, result)
        assertEquals(JsonPrimitive(true), result["unlocked"])
        assertEquals(1, f.strokes.size)
        assertEquals(3, f.snapshots)
        assertSequence(complete, pin, confirm = true)
        assertPrivate(f, pin, result)
    }

    @Test fun swipeOnlyKeyguardReportsSuccessWhenTheRevealDismissesIt() = runTest {
        val f = Fixture(testScheduler).apply { show(PinPadFixtures.oneUi(missing = '7')) }
        f.afterSwipe = { f.showing = false }
        val result = f.send(syntheticPin()).result
        assertTrue("A reveal that unlocks the phone must report success.", result["unlocked"] == JsonPrimitive(true))
        assertTrue("Swipe-only unlock must never enter any keys.", f.layout.clicks.isEmpty())
        assertEquals(1, f.strokes.size)
    }

    @Test fun unstableKeyguardStopsAtTheSettlingBudgetWithoutAnyRevealOrEntry() = runTest {
        val f = Fixture(testScheduler).apply {
            show(PinPadFixtures.oneUi(missing = '7'))
            keyguardSample = { KeyguardSnapshot(true, (clock.monotonicMs() / 250) % 2 == 0L) }
        }
        val pin = syntheticPin()
        val result = f.send(pin).result
        assertFailure(f, pin, result, "pin_pad_not_found", attempted = false)
        assertEquals(2_000L, currentTime)
        assertTrue(f.strokes.isEmpty() && f.layout.clicks.isEmpty())
    }

    @Test fun settlingReadFailureKeepsItsCauseAndStopsBeforeRevealOrEntry() = runTest {
        val f = Fixture(testScheduler).apply { show(PinPadFixtures.oneUi(missing = '7')) }
        val pin = syntheticPin()
        f.keyguardFailure = PortFailure(FailureKind.PLATFORM, IllegalStateException(pin))
        f.failKeyguardAfter = 1
        val result = f.send(pin).result
        assertFailure(f, pin, result, "reveal_pin_pad", attempted = false)
        assertEquals("error", f.logger.entries.last().level)
        assertTrue(f.strokes.isEmpty() && f.layout.clicks.isEmpty())
        assertEquals(0, currentTime)
    }

    @Test fun aKeyguardThatDisappearsWhileSettlingNeedsNoRevealOrEntry() = runTest {
        val f = Fixture(testScheduler).apply {
            show(PinPadFixtures.oneUi(missing = '7'))
            keyguardSample = { KeyguardSnapshot(keyguardReads <= 1, true) }
        }
        val pin = syntheticPin()
        val result = f.send(pin).result
        assertPrivate(f, pin, result)
        assertEquals(buildJsonObject { put("unlocked", true); put("success", true) }, result)
        assertTrue(f.strokes.isEmpty() && f.layout.clicks.isEmpty())
        assertEquals(500L, currentTime)
    }

    @Test fun anUnacceptedSequenceWaitsFiveSecondsAndAReplayNeverTypesAgain() = runTest {
        val f = Fixture(testScheduler)
        val pin = syntheticPin()
        val first = f.send(pin)
        assertFailure(f, pin, first.result, "pin_not_accepted", attempted = true)
        assertSequence(f.layout, pin, confirm = true)
        assertEquals(5_000L + 120L * (pin.length - 1), currentTime)
        assertSame(first, f.send(pin))
        assertSequence(f.layout, pin, confirm = true)
        assertEquals(1, f.wakes)
    }

    @Test fun wakeRefusalInteractiveReadFailureAndTimeoutAllRefuseBeforeThePad() = runTest {
        for (kind in listOf("wake", "read", "timeout")) {
            val f = Fixture(testScheduler)
            val pin = syntheticPin()
            val failure = PortFailure(FailureKind.PLATFORM, IllegalStateException(pin))
            when (kind) {
                "wake" -> f.wakeFailure = failure
                "read" -> f.interactiveFailure = failure
                else -> f.interactive = false
            }
            val before = currentTime
            assertFailure(f, pin, f.send(pin).result, "wake", attempted = false, code = "wake_failed")
            assertEquals(if (kind == "timeout") 2_000L else 0L, currentTime - before)
            assertEquals(0, f.snapshots)
            assertTrue(f.strokes.isEmpty() && f.layout.clicks.isEmpty())
        }
    }

    @Test fun unreadableKeyguardFailsWithTheCauseAndNoWake() = runTest {
        val f = Fixture(testScheduler)
        val pin = syntheticPin()
        val cause = IllegalStateException(pin)
        f.keyguardFailure = PortFailure(FailureKind.PLATFORM, cause)
        assertFailure(f, pin, f.send(pin).result, "keyguard", attempted = false)
        assertEquals(0, f.wakes)
        val logged = f.logger.entries.single()
        assertEquals("error", logged.level)
        assertTrue("The original failure stack must be retained.", logged.cause!!.cause!!.stackTrace.contentEquals(cause.stackTrace))
    }

    @Test fun invalidBodiesMakeNoUnlockPortCallAfterTheRouterAccessibilityPrerequisite() = runTest {
        val bodies = listOf(JsonObject(emptyMap()), buildJsonObject { put("pin", "letters") },
            buildJsonObject { put("pin", syntheticPin(3)) }, buildJsonObject { put("pin", syntheticPin(17)) },
            buildJsonObject { put("pin", 0) }, buildJsonObject { put("pin", JsonNull) })
        for (body in bodies) {
            val f = Fixture(testScheduler)
            val result = f.sendBody(body).result
            assertPrivate(f, null, result)
            assertEquals(buildJsonObject {
                put("success", false); put("error", "Parameter 'pin' is missing or invalid."); put("code", "bad_request")
            }, result)
            assertEquals(1, f.tree.connectionReads)
            assertEquals(0, f.keyguardReads); assertEquals(0, f.wakes); assertEquals(0, f.snapshots); assertEquals(0, f.metricsReads)
            assertTrue(f.strokes.isEmpty() && f.layout.clicks.isEmpty())
            assertEquals(0, f.gate.calls)
            assertPrivate(f, null, result)
        }
    }

    @Test fun revealFailuresKeepTheirCauseAndNeverBecomeACompletePad() = runTest {
        for (kind in listOf("metrics", "gesture", "tree")) {
            val f = Fixture(testScheduler).apply { show(PinPadFixtures.oneUi(missing = '7')) }
            val pin = syntheticPin()
            val failure = PortFailure(FailureKind.PLATFORM, IllegalStateException(pin))
            when (kind) {
                "metrics" -> f.metricsFailure = failure
                "gesture" -> f.gestureFailure = failure
                else -> f.tree.windowResult = PortResult.Failure(failure)
            }
            assertFailure(f, pin, f.send(pin).result, "reveal_pin_pad", attempted = false)
            assertTrue(f.layout.clicks.isEmpty())
            assertEquals("error", f.logger.entries.last().level)
        }
    }

    @Test fun aRefusedDigitStopsTheOnlySequenceAndRedactsPrivateNodeTextAndCause() = runTest {
        val f = Fixture(testScheduler)
        val pin = syntheticPin()
        val key = f.layout.digits.getValue(pin.first())
        key.properties = key.properties.copy(contentDescription = "private-node-label")
        key.actionResult = PortResult.Failure(PortFailure(FailureKind.REJECTED, IllegalStateException(pin)))
        assertFailure(f, pin, f.send(pin).result, "enter_pin", attempted = true)
        assertEquals(1, f.layout.clicks.size)
        assertEquals(0, currentTime)
    }

    @Test fun aRefusedConfirmIsAnAttemptedFailureWithoutAnySecondEntry() = runTest {
        val f = Fixture(testScheduler)
        val pin = syntheticPin()
        f.layout.confirm!!.actionResult = PortResult.Failure(PortFailure(FailureKind.REJECTED, IllegalStateException(pin)))
        assertFailure(f, pin, f.send(pin).result, "confirm", attempted = true)
        assertSequence(f.layout, pin, confirm = true)
    }

    @Test fun unreadableKeyguardAfterEntryNeverClaimsSuccess() = runTest {
        val f = Fixture(testScheduler)
        val pin = syntheticPin()
        f.keyguardFailure = PortFailure(FailureKind.PLATFORM, IllegalStateException(pin))
        f.failKeyguardAfter = 1
        assertFailure(f, pin, f.send(pin).result, "pin_not_accepted", attempted = true)
        assertSequence(f.layout, pin, confirm = true)
    }

    @Test fun disconnectedAccessibilityRefusesBeforeAnyUnlockOperation() = runTest {
        val f = Fixture(testScheduler).apply { tree.connection = PortResult.Success(false) }
        val pin = syntheticPin()
        val result = f.send(pin).result
        assertPrivate(f, pin, result)
        assertEquals(JsonPrimitive("accessibility_disabled"), result["code"])
        assertEquals(1, f.tree.connectionReads)
        assertEquals(0, f.keyguardReads); assertEquals(0, f.wakes); assertEquals(0, f.gate.calls)
        assertPrivate(f, pin, result)
    }
}
