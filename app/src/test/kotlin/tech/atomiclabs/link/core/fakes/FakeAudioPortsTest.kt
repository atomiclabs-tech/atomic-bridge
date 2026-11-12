package tech.atomiclabs.link.core.fakes

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import tech.atomiclabs.link.core.port.*

class FakeAudioPortsTest {
    @Test fun recordsPerRouteOperationsAndPreservesDependencyFailures() = runTest {
        val fake = FakeAudioPorts()
        val sample = (fake.create(AudioCaptureSource.MIC, true) as PortResult.Success).value
        val capture = AudioCaptureRequest(AudioCaptureSource.MIC, true, 1_000, sample)
        val playback = AudioPlaybackRequest(AudioPlaybackStream.USAGE_MEDIA, true)
        fake.capture(capture); fake.play(playback); fake.setSpeaker(true)
        assertEquals(listOf(capture), fake.captures)
        assertEquals(listOf(playback), fake.plays)
        assertEquals(listOf(true), fake.speakerRequests)
        assertEquals(listOf(AudioCaptureSource.MIC to true), fake.sampleRequests)
        val failure = PortFailure(FailureKind.PERMISSION_DENIED, IllegalStateException("fixture"), "android.permission.RECORD_AUDIO")
        fake.failure = failure
        val expected = PortResult.Failure(failure)
        assertEquals(expected, fake.capture(capture))
        assertEquals(expected, fake.play(playback))
        assertEquals(expected, fake.startMicrophone())
        assertEquals(expected, fake.stopMicrophone())
        assertEquals(expected, fake.clear())
        assertEquals(expected, fake.read())
        assertEquals(expected, fake.callState())
        assertEquals(expected, fake.run(AudioProbePlan()))
    }
}
