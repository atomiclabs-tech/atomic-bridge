package tech.atomiclabs.link.device.phone

import android.content.Context
import android.media.AudioManager
import android.view.KeyEvent
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import tech.atomiclabs.link.core.command.CommandRouter
import tech.atomiclabs.link.core.fakes.*
import tech.atomiclabs.link.core.phone.MediaKeyRoutes
import tech.atomiclabs.link.core.port.*
import tech.atomiclabs.link.core.protocol.BridgeCommand

@OptIn(ExperimentalCoroutinesApi::class)
class AndroidMediaKeysTest {
    private val context = mockk<Context>()
    private val audio = mockk<AudioManager>()
    private val logger = FakeLogger()
    private val events = mutableListOf<Pair<Int, Int>>()
    private val codes = listOf(126, 127, 85, 87, 88)

    @Before fun prepare() {
        mockkConstructor(KeyEvent::class)
        for (action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) for (code in codes) {
            every { constructedWith<KeyEvent>(EqMatcher(action), EqMatcher(code)).action } returns action
            every { constructedWith<KeyEvent>(EqMatcher(action), EqMatcher(code)).keyCode } returns code
        }
        every { context.getSystemService(AudioManager::class.java) } returns audio
        every { audio.dispatchMediaKeyEvent(any()) } answers {
            val event = firstArg<KeyEvent>()
            events += event.action to event.keyCode
        }
    }
    @After fun cleanup() { unmockkConstructor(KeyEvent::class) }

    @Test fun fiveActionsReachAudioDispatcherDownThenUpWithExactPlatformCodes() = runTest {
        val adapter = AndroidMediaKeys(context, logger, StandardTestDispatcher(testScheduler))
        for ((action, code) in MediaAction.entries.zip(codes)) {
            assertEquals(PortResult.Success(code), adapter.send(action))
            assertEquals(listOf(KeyEvent.ACTION_DOWN to code, KeyEvent.ACTION_UP to code), events.takeLast(2))
        }
        verify(exactly = 10) { audio.dispatchMediaKeyEvent(any()) }
        verify(exactly = 0) { context.sendOrderedBroadcast(any(), null) }
        assertTrue(logger.entries.isEmpty())
    }

    @Test fun missingAudioServiceIsLoggedFailureWithoutBroadcastFallback() = runTest {
        every { context.getSystemService(AudioManager::class.java) } returns null
        val result = AndroidMediaKeys(context, logger, StandardTestDispatcher(testScheduler))
            .send(MediaAction.PLAY) as PortResult.Failure
        assertEquals(FailureKind.PLATFORM, result.failure.kind)
        assertTrue(result.failure.cause is IllegalStateException)
        verify(exactly = 0) { audio.dispatchMediaKeyEvent(any()) }
        verify(exactly = 0) { context.sendOrderedBroadcast(any(), null) }
        assertEquals("error", logger.entries.single().level)
    }

    @Test fun refusedDownRetainsCauseLogsSafelyAndNeverSendsUpOrRetries() = runTest {
        val cause = SecurityException("private-media-fixture")
        every { audio.dispatchMediaKeyEvent(any()) } throws cause
        val result = AndroidMediaKeys(context, logger, StandardTestDispatcher(testScheduler))
            .send(MediaAction.PLAY) as PortResult.Failure
        assertEquals(FailureKind.PERMISSION_DENIED, result.failure.kind)
        assertTrue(generateSequence(result.failure.cause) { it.cause }.any { it === cause })
        verify(exactly = 1) { audio.dispatchMediaKeyEvent(any()) }
        assertEquals("error", logger.entries.single().level)
        assertFalse(logger.entries.single().cause!!.stackTraceToString().contains("private-media-fixture"))
    }

    @Test fun failedUpDoesNotClaimSuccessOrRepeatEitherEvent() = runTest {
        val cause = IllegalStateException("private-up-fixture")
        every { audio.dispatchMediaKeyEvent(match { it.action == KeyEvent.ACTION_UP }) } throws cause
        val result = AndroidMediaKeys(context, logger, StandardTestDispatcher(testScheduler))
            .send(MediaAction.NEXT) as PortResult.Failure
        assertEquals(FailureKind.PLATFORM, result.failure.kind)
        assertTrue(generateSequence(result.failure.cause) { it.cause }.any { it === cause })
        assertEquals(listOf(KeyEvent.ACTION_DOWN to 87), events)
        verify(exactly = 2) { audio.dispatchMediaKeyEvent(any()) }
        assertEquals("error", logger.entries.single().level)
        assertFalse(logger.entries.single().cause!!.stackTraceToString().contains("private-up-fixture"))
    }

    @Test fun serviceLookupFailureDoesNotDispatch() = runTest {
        val cause = SecurityException("private-service-fixture")
        every { context.getSystemService(AudioManager::class.java) } throws cause
        val result = AndroidMediaKeys(context, logger, StandardTestDispatcher(testScheduler))
            .send(MediaAction.PAUSE) as PortResult.Failure
        assertEquals(FailureKind.PERMISSION_DENIED, result.failure.kind)
        assertTrue(generateSequence(result.failure.cause) { it.cause }.any { it === cause })
        verify(exactly = 0) { audio.dispatchMediaKeyEvent(any()) }
        assertEquals("error", logger.entries.single().level)
        assertFalse(logger.entries.single().cause!!.stackTraceToString().contains("private-service-fixture"))
    }

    @Test fun cancellationPropagatesWithoutFailureLogOrSecondEvent() = runTest {
        val cause = CancellationException("cancelled fixture")
        every { audio.dispatchMediaKeyEvent(any()) } throws cause
        try {
            AndroidMediaKeys(context, logger, StandardTestDispatcher(testScheduler)).send(MediaAction.TOGGLE)
            fail("Cancellation must propagate")
        } catch (actual: CancellationException) {
            assertTrue(generateSequence<Throwable>(actual) { it.cause }.any { it === cause })
        }
        verify(exactly = 1) { audio.dispatchMediaKeyEvent(any()) }
        assertTrue(logger.entries.isEmpty())
    }

    @Test fun fiveSecondDeadlineStopsBeforeSecondEventAndReturnsTypedFailure() = runTest {
        every { audio.dispatchMediaKeyEvent(any()) } answers { testScheduler.advanceTimeBy(5_001) }
        val result = AndroidMediaKeys(context, logger, StandardTestDispatcher(testScheduler))
            .send(MediaAction.PREVIOUS) as PortResult.Failure
        assertEquals(FailureKind.TIMED_OUT, result.failure.kind)
        verify(exactly = 1) { audio.dispatchMediaKeyEvent(any()) }
        assertEquals("error", logger.entries.single().level)
        assertEquals(5_001, currentTime)
    }

    @Test fun actualDispatcherFailureReachesRouterAsErrorAndReplayDoesNotDispatchAgain() = runTest {
        every { audio.dispatchMediaKeyEvent(any()) } throws SecurityException("private-dispatch-fixture")
        val adapter = AndroidMediaKeys(context, logger, StandardTestDispatcher(testScheduler))
        val tree = FakeUiTree().apply { connection = PortResult.Success(false) }
        val gate = FakeDisplayGate()
        val router = CommandRouter(listOf(MediaKeyRoutes(adapter, logger)), tree, gate, logger)
        val command = BridgeCommand("media-failure", "POST", "/media", body = buildJsonObject { put("action", "pause") })
        val response = router.dispatch(command)
        assertEquals(200, response.status)
        assertEquals(JsonPrimitive(false), response.result["success"])
        assertEquals(JsonPrimitive("permission_denied"), response.result["code"])
        assertFalse(response.result.containsKey("keycode"))
        assertSame(response, router.dispatch(command))
        verify(exactly = 1) { audio.dispatchMediaKeyEvent(any()) }
        assertEquals(0, gate.calls)
        assertEquals(2, logger.entries.size)
        assertTrue(logger.entries.all { it.level == "error" && !it.cause!!.stackTraceToString().contains("private-") })
    }
}
