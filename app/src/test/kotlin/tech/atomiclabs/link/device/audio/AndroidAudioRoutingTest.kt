package tech.atomiclabs.link.device.audio

import android.media.AudioDeviceInfo
import android.media.AudioManager
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import tech.atomiclabs.link.core.fakes.*
import tech.atomiclabs.link.core.port.*

@OptIn(ExperimentalCoroutinesApi::class)
class AndroidAudioRoutingTest {
    @Test fun selectsAndChecksCommunicationDeviceForEachSpeakerStateAndDelegatesCallState() = runTest {
        val manager = mockk<AudioManager>(); val speaker = mockk<AudioDeviceInfo>(); val earpiece = mockk<AudioDeviceInfo>()
        every { speaker.type } returns AudioDeviceInfo.TYPE_BUILTIN_SPEAKER; every { speaker.id } returns 3
        every { earpiece.type } returns AudioDeviceInfo.TYPE_BUILTIN_EARPIECE; every { earpiece.id } returns 4
        every { manager.availableCommunicationDevices } returns listOf(speaker, earpiece)
        var selected: AudioDeviceInfo? = null
        every { manager.setCommunicationDevice(any()) } answers { selected = firstArg(); true }
        every { manager.communicationDevice } answers { selected }
        val logger = FakeLogger(); val state = FakeAudioPorts()
        val routing = AndroidAudioRouting(manager, FakeUiTree(), state, FakeClock(testScheduler), logger, StandardTestDispatcher(testScheduler))
        for (enabled in listOf(false, true)) {
            val attempts = (routing.setSpeaker(enabled) as PortResult.Success).value
            assertEquals(listOf(SpeakerAttempt(enabled, "communication_device", true)), attempts)
        }
        assertEquals(PortResult.Success(CallState.OFFHOOK), routing.callState())
        assertTrue(logger.entries.isEmpty())
        verify(exactly = 0) { manager.isSpeakerphoneOn = any() }
    }

    @Test fun failedCommunicationMethodFallsBackWithWarningAndChecksSpeakerphone() = runTest {
        val manager = mockk<AudioManager>(); var speaker = false
        every { manager.availableCommunicationDevices } returns emptyList()
        every { manager.isSpeakerphoneOn = any() } answers { speaker = firstArg() }
        every { manager.isSpeakerphoneOn } answers { speaker }
        val logger = FakeLogger()
        val routing = AndroidAudioRouting(manager, FakeUiTree(), FakeAudioPorts(), FakeClock(testScheduler), logger,
            StandardTestDispatcher(testScheduler))
        val attempts = (routing.setSpeaker(true) as PortResult.Success).value
        assertEquals(listOf("communication_device", "speakerphone"), attempts.map { it.via })
        assertFalse(attempts.first().switched); assertTrue(attempts.last().switched)
        assertTrue(logger.entries.any { it.level == "warn" && it.operation == "speaker_fallback" })
    }

    @Test fun visibleSpeakerActionIsMeasuredAndAllFailedMethodsKeepErrorsWithoutPrivateLogMessages() = runTest {
        val manager = mockk<AudioManager>(); var speaker = false
        every { manager.availableCommunicationDevices } throws SecurityException("private route detail")
        every { manager.isSpeakerphoneOn = any() } throws IllegalStateException("speaker setter rejected")
        every { manager.isSpeakerphoneOn } answers { speaker }
        val node = mockk<UiNode>()
        coEvery { node.read() } returns PortResult.Success(UiNodeProperties(Bounds(0, 0, 100, 100), 1, text = "Speaker", clickable = true))
        coEvery { node.perform(NodeAction.Simple(NodeActionKind.CLICK)) } answers { speaker = true; PortResult.Success(Unit) }
        val tree = FakeUiTree().apply { root = ReadValue.Present(node) }
        val logger = FakeLogger()
        val routing = AndroidAudioRouting(manager, tree, FakeAudioPorts(), FakeClock(testScheduler), logger,
            StandardTestDispatcher(testScheduler))
        val attempts = (routing.setSpeaker(true) as PortResult.Success).value
        assertEquals(listOf("communication_device", "speakerphone", "accessibility_speaker"), attempts.map { it.via })
        assertEquals(listOf(false, false, true), attempts.map { it.switched })
        assertEquals("private route detail", attempts.first().error)
        speaker = false
        coEvery { node.perform(any()) } returns PortResult.Failure(PortFailure(FailureKind.REJECTED, IllegalStateException("tap rejected")))
        val failed = (routing.setSpeaker(true) as PortResult.Success).value
        assertTrue(failed.all { !it.switched && it.error != null })
        assertTrue(logger.entries.filter { it.level == "error" }.all { !it.cause!!.stackTraceToString().contains("private route detail") })
    }

    @Test fun unchangedRouteTimesOutAndCallerCancellationIsPropagated() = runTest {
        val manager = mockk<AudioManager>(); val target = mockk<AudioDeviceInfo>()
        every { target.type } returns AudioDeviceInfo.TYPE_BUILTIN_SPEAKER; every { target.id } returns 3
        every { manager.availableCommunicationDevices } returns listOf(target)
        every { manager.setCommunicationDevice(target) } returns true
        every { manager.communicationDevice } returns null
        every { manager.isSpeakerphoneOn = any() } just Runs
        every { manager.isSpeakerphoneOn } returns false
        val clock = FakeClock(testScheduler)
        val routing = AndroidAudioRouting(manager, FakeUiTree(), FakeAudioPorts(), clock, FakeLogger(), StandardTestDispatcher(testScheduler))
        val attempts = (routing.setSpeaker(true) as PortResult.Success).value
        assertFalse(attempts.first().switched); assertFalse(attempts[1].switched)
        assertEquals(4_000L, clock.monotonicMs())
        val job = launch { routing.setSpeaker(true) }
        runCurrent(); job.cancelAndJoin(); assertTrue(job.isCancelled)
    }
}
