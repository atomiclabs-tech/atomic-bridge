package tech.atomiclabs.link.device.access

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import tech.atomiclabs.link.core.fakes.FakeClock
import tech.atomiclabs.link.core.fakes.FakeLogger
import tech.atomiclabs.link.core.port.*

@OptIn(ExperimentalCoroutinesApi::class)
class GestureDispatcherTest {
    private val service = mockk<AccessibilityService>()
    private val logger = FakeLogger()
    private val gesture = mockk<GestureDescription>()
    private val callback = slot<AccessibilityService.GestureResultCallback>()
    private val strokes = mutableListOf<GestureDescription.StrokeDescription>()
    private val commands = mutableListOf<Pair<String, Point>>()
    private val input = listOf(GestureStroke(listOf(Point(1f, 2f), Point(8f, 9f)), 300))

    @Before fun platform() {
        mockkConstructor(Path::class, GestureDescription.Builder::class, GestureDescription.StrokeDescription::class)
        every { anyConstructed<Path>().moveTo(any(), any()) } answers {
            commands += "moveTo" to Point(firstArg(), secondArg())
        }
        every { anyConstructed<Path>().lineTo(any(), any()) } answers {
            commands += "lineTo" to Point(firstArg(), secondArg())
        }
        every { anyConstructed<GestureDescription.Builder>().addStroke(capture(strokes)) } answers { self as GestureDescription.Builder }
        every { anyConstructed<GestureDescription.Builder>().build() } returns gesture
        every { constructedWith<GestureDescription.StrokeDescription>(OfTypeMatcher<Path>(Path::class), EqMatcher(0L), EqMatcher(300L)).duration } returns 300
        every { service.dispatchGesture(any(), capture(callback), isNull()) } returns true
    }
    @After fun cleanup() { unmockkConstructor(Path::class, GestureDescription.Builder::class, GestureDescription.StrokeDescription::class) }
    private fun TestScope.adapter(connected: Boolean = true): GestureDispatcher {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val hub = AccessibilityHub(logger, FakeClock(testScheduler), dispatcher)
        if (connected) hub.onConnected(service)
        return GestureDispatcher(hub, logger, dispatcher)
    }

    @Test fun dispatchBuildsThePathAndStrokeThenWaitsForCompletion() = runTest {
        val result = async { adapter().dispatch(input) }; runCurrent()
        assertFalse(result.isCompleted)
        callback.captured.onCompleted(gesture)
        assertEquals(PortResult.Success(Unit), result.await())
        verifyOrder { anyConstructed<Path>().moveTo(1f, 2f); anyConstructed<Path>().lineTo(8f, 9f) }
        assertEquals(300, strokes.single().duration)
        verify(exactly = 1) { service.dispatchGesture(gesture, any(), isNull()) }
    }

    @Test fun refusedDispatchAndCancelledCallbackReturnLoggedFailures() = runTest {
        val source = adapter()
        every { service.dispatchGesture(any(), any(), isNull()) } returns false
        assertEquals(FailureKind.REJECTED, (source.dispatch(input) as PortResult.Failure).failure.kind)
        every { service.dispatchGesture(any(), capture(callback), isNull()) } returns true
        val result = async { source.dispatch(input) }; runCurrent(); callback.captured.onCancelled(gesture)
        assertEquals(FailureKind.REJECTED, (result.await() as PortResult.Failure).failure.kind)
        assertEquals(2, logger.entries.count { it.level == "error" })
    }

    @Test fun missingCallbackTimesOutAfterDurationPlusFiveSecondsAndCallerCancellationRejectsLateCallbacks() = runTest {
        val source = adapter()
        val result = async { source.dispatch(input) }; runCurrent()
        advanceTimeBy(5_299); runCurrent(); assertFalse(result.isCompleted)
        advanceTimeBy(1); runCurrent()
        assertEquals(FailureKind.TIMED_OUT, (result.await() as PortResult.Failure).failure.kind)
        callback.captured.onCompleted(gesture)
        val cancelled = launch { source.dispatch(input) }; runCurrent(); cancelled.cancelAndJoin()
        callback.captured.onCancelled(gesture)
        assertEquals(1, logger.entries.count { it.level == "error" })
        assertEquals(2, logger.entries.count { it.operation == "callback" && it.level == "warn" })
    }

    @Test fun missingServiceAndLaterPermissionFailureNeverBecomeSuccessOrPrivateLogs() = runTest {
        assertEquals(FailureKind.UNAVAILABLE, (adapter(false).dispatch(input) as PortResult.Failure).failure.kind)
        val cause = SecurityException("private fixture payload")
        every { service.dispatchGesture(any(), any(), isNull()) } throws cause
        val failed = adapter().dispatch(input) as PortResult.Failure
        assertEquals(FailureKind.PERMISSION_DENIED, failed.failure.kind)
        assertTrue(generateSequence(failed.failure.cause) { it.cause }.any { it === cause })
        assertFalse(logger.entries.any { it.cause?.stackTraceToString()?.contains("private fixture payload") == true })
    }

    private fun heldInput(hold: Long = 800, duration: Long = 300) = input.map { it.copy(holdMs = hold, durationMs = duration) }

    private fun continuationStroke(): GestureDescription.StrokeDescription {
        val continued = mockk<GestureDescription.StrokeDescription>()
        every { constructedWith<GestureDescription.StrokeDescription>(OfTypeMatcher<Path>(Path::class),
            EqMatcher(0L), EqMatcher(800L), EqMatcher(true)).duration } returns 800
        every { constructedWith<GestureDescription.StrokeDescription>(OfTypeMatcher<Path>(Path::class),
            EqMatcher(0L), EqMatcher(800L), EqMatcher(true)).continueStroke(any(), 0, 300, false) } returns continued
        return continued
    }

    @Test fun heldStrokeWaitsForCompletionBeforeContinuingOnTheMainDispatcher() = runTest {
        val continued = continuationStroke()
        val result = async { adapter().dispatch(heldInput()) }; runCurrent()
        assertEquals(800, strokes.single().duration)
        verify(exactly = 2) { anyConstructed<Path>().lineTo(any(), any()) }
        val firstCallback = callback.captured
        firstCallback.onCompleted(gesture)
        verify(exactly = 1) { service.dispatchGesture(any(), any(), isNull()) }
        runCurrent()
        assertEquals(2, strokes.size)
        assertSame(continued, strokes.last())
        verify(exactly = 1) { constructedWith<GestureDescription.StrokeDescription>(OfTypeMatcher<Path>(Path::class),
            EqMatcher(0L), EqMatcher(800L), EqMatcher(true)).continueStroke(any(), 0, 300, false) }
        verify(exactly = 1) { anyConstructed<Path>().lineTo(8f, 9f) }
        assertFalse(result.isCompleted)
        firstCallback.onCompleted(gesture)
        firstCallback.onCancelled(gesture)
        runCurrent()
        verify(exactly = 2) { service.dispatchGesture(any(), any(), isNull()) }
        callback.captured.onCompleted(gesture)
        assertEquals(PortResult.Success(Unit), result.await())
        assertEquals(2, logger.entries.count { it.level == "warn" && it.operation == "callback" })
    }

    @Test fun cancelledHoldNeverDispatchesTheContinuation() = runTest {
        continuationStroke()
        val result = async { adapter().dispatch(heldInput()) }; runCurrent()
        callback.captured.onCancelled(gesture)
        assertEquals(FailureKind.REJECTED, (result.await() as PortResult.Failure).failure.kind)
        verify(exactly = 1) { service.dispatchGesture(any(), any(), isNull()) }
        verify(exactly = 0) { anyConstructed<GestureDescription.StrokeDescription>().continueStroke(any(), any(), any(), any()) }
        assertNotNull(logger.entries.single { it.level == "error" }.cause)
    }

    @Test fun refusedContinuationReturnsALoggedFailureWithTheCause() = runTest {
        continuationStroke()
        every { service.dispatchGesture(any(), capture(callback), isNull()) } returnsMany listOf(true, false)
        val result = async { adapter().dispatch(heldInput()) }; runCurrent()
        callback.captured.onCompleted(gesture); runCurrent()
        val failed = result.await() as PortResult.Failure
        assertEquals(FailureKind.REJECTED, failed.failure.kind)
        assertTrue(failed.failure.cause is IllegalStateException)
        assertNotNull(logger.entries.single { it.level == "error" }.cause)
        callback.captured.onCompleted(gesture)
        verify(exactly = 2) { service.dispatchGesture(any(), any(), isNull()) }
    }

    @Test fun cancelledContinuationReturnsARejectedFailure() = runTest {
        continuationStroke()
        val result = async { adapter().dispatch(heldInput()) }; runCurrent()
        callback.captured.onCompleted(gesture); runCurrent()
        callback.captured.onCancelled(gesture)
        assertEquals(FailureKind.REJECTED, (result.await() as PortResult.Failure).failure.kind)
        verify(exactly = 2) { service.dispatchGesture(any(), any(), isNull()) }
    }

    @Test fun heldGestureTimeoutIncludesTheHoldAndMovementAndRejectsALateContinuation() = runTest {
        val result = async { adapter().dispatch(heldInput(3000, 3000)) }; runCurrent()
        advanceTimeBy(10_999); runCurrent(); assertFalse(result.isCompleted)
        advanceTimeBy(1); runCurrent()
        assertEquals(FailureKind.TIMED_OUT, (result.await() as PortResult.Failure).failure.kind)
        assertEquals(11_000, currentTime)
        callback.captured.onCompleted(gesture); runCurrent()
        verify(exactly = 1) { service.dispatchGesture(any(), any(), isNull()) }
        assertNotNull(logger.entries.single { it.level == "error" }.cause)
    }

    @Test fun actualHeldConstructionAtTheTopLeftHasDurationAndClosedOnePixelPath() = runTest {
        assertHeldConstruction(Point(0f, 0f), Point(1f, 0f))
    }

    @Test fun actualHeldConstructionAtTheBottomRightMovesInwardAndReturnsExactly() = runTest {
        assertHeldConstruction(Point(1079f, 2399f), Point(1078f, 2399f))
    }

    private suspend fun TestScope.assertHeldConstruction(start: Point, neighbor: Point) {
        val actualPaths = mutableListOf<Path>()
        val actualDurations = mutableListOf<Long>()
        val pathArgument = object : Matcher<Path> {
            override fun match(arg: Path?): Boolean {
                if (arg == null) return false
                actualPaths += arg
                return true
            }
        }
        val durationArgument = object : Matcher<Long> {
            override fun match(arg: Long?): Boolean {
                if (arg == null) return false
                actualDurations += arg
                return true
            }
        }
        val continued = mockk<GestureDescription.StrokeDescription>()
        every { constructedWith<GestureDescription.StrokeDescription>(pathArgument, EqMatcher(0L),
            durationArgument, EqMatcher(true)).continueStroke(any(), 0, 800, false) } returns continued
        val result = async { adapter().dispatch(listOf(GestureStroke(listOf(start, Point(20f, 30f)), 800, holdMs = 3000))) }
        runCurrent()
        // Capture the actual constructor arguments; do not stub getDuration() to the requested hold.
        assertEquals(listOf(3000L), actualDurations)
        assertEquals(1, actualPaths.size)
        assertEquals(listOf("moveTo" to start, "lineTo" to neighbor, "lineTo" to start), commands)
        assertTrue(commands.all { (_, point) -> point.x >= 0 && point.y >= 0 &&
            kotlin.math.abs(point.x - start.x) <= 1 && point.y == start.y })
        advanceTimeBy(2999); runCurrent()
        verify(exactly = 1) { service.dispatchGesture(any(), any(), isNull()) }
        // Virtual time here drives callbacks only; it does not emulate Android's gesture clock.
        advanceTimeBy(1); callback.captured.onCompleted(gesture); runCurrent()
        assertSame(continued, strokes.last())
        assertEquals(listOf("moveTo" to start, "lineTo" to Point(20f, 30f)), commands.takeLast(2))
        verify(exactly = 1) { constructedWith<GestureDescription.StrokeDescription>(pathArgument, EqMatcher(0L),
            durationArgument, EqMatcher(true)).continueStroke(any(), 0, 800, false) }
        assertFalse(result.isCompleted)
        callback.captured.onCompleted(gesture)
        assertEquals(PortResult.Success(Unit), result.await())
    }
}
