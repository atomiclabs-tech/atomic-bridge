package tech.atomiclabs.link.device.audio

import android.media.AudioRecord
import android.media.MediaRecorder
import io.mockk.*
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import tech.atomiclabs.link.core.audio.ProbeTone
import tech.atomiclabs.link.core.fakes.*
import tech.atomiclabs.link.core.port.*

@OptIn(ExperimentalCoroutinesApi::class)
class AndroidAudioProbeTest {
    private fun recorder(): AudioRecord = mockk<AudioRecord>().apply {
        every { state } returns AudioRecord.STATE_INITIALIZED
        every { startRecording() } just Runs
        every { recordingState } returns AudioRecord.RECORDSTATE_RECORDING
        every { stop() } just Runs
        every { release() } just Runs
        every { read(any<ByteArray>(), any(), any(), AudioRecord.READ_NON_BLOCKING) } answers {
            val buffer = firstArg<ByteArray>(); val offset = secondArg<Int>(); val count = thirdArg<Int>()
            ProbeTone.pcm(durationMs = 1_000).copyInto(buffer, offset, offset, offset + count)
            count
        }
    }

    @Test fun everyApprovedSourceWritesMeasuredPcmAndValidPrivateWavAndClosesOnce() = runTest {
        val logger = FakeLogger(); val record = recorder(); val sourceIds = mutableListOf<Int>()
        val adapter = AndroidAudioProbe(FakeClock(testScheduler), logger, StandardTestDispatcher(testScheduler),
            { 256 }, { source, size -> assertEquals(1_600, size); sourceIds += source; record })
        for (source in AudioCaptureSource.entries) {
            val handle = FakeFileWriteHandle(-1)
            val request = AudioCaptureRequest(source, false, 1_000, AudioSampleFile("private/audio-probe/sample.wav", handle))
            val value = (adapter.capture(request) as PortResult.Success).value
            assertEquals(source, value.source); assertFalse(value.speaker)
            assertEquals(AudioInitialization.OK, value.init); assertEquals(32_000L, value.bytes)
            assertEquals(0.25, value.peak, 0.0001); assertFalse(value.silent)
            assertEquals(request.sample.path, value.sample); assertNull(value.error)
            assertTrue(handle.closed); assertFalse(handle.discarded)
            assertEquals(32_044, handle.bytes.size)
            assertEquals(32_000, ByteBuffer.wrap(handle.bytes).order(ByteOrder.LITTLE_ENDIAN).getInt(40))
        }
        assertEquals(listOf(MediaRecorder.AudioSource.VOICE_CALL, MediaRecorder.AudioSource.VOICE_DOWNLINK,
            MediaRecorder.AudioSource.VOICE_UPLINK, MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.MIC,
            MediaRecorder.AudioSource.UNPROCESSED), sourceIds)
        verify(exactly = 7) { record.stop(); record.release() }
        assertTrue(logger.entries.isEmpty())
    }

    @Test fun constructorInitializationReadAndFileFailuresDiscardAndKeepExactCauseWithSanitizedLogs() = runTest {
        val logger = FakeLogger(); val record = recorder()
        var build: () -> AudioRecord = { throw SecurityException("private constructor detail") }
        val adapter = AndroidAudioProbe(FakeClock(testScheduler), logger, StandardTestDispatcher(testScheduler), { 256 }, { _, _ -> build() })
        suspend fun failure(): PortResult.Failure {
            val handle = FakeFileWriteHandle(-1)
            val result = adapter.capture(AudioCaptureRequest(AudioCaptureSource.VOICE_CALL, false, 1_000,
                AudioSampleFile("private/audio-probe/sample.wav", handle))) as PortResult.Failure
            assertTrue(handle.discarded); return result
        }
        val denied = failure()
        assertEquals(FailureKind.PERMISSION_DENIED, denied.failure.kind)
        assertNull(denied.failure.permission)
        assertEquals("private constructor detail", denied.failure.cause.message)
        build = { record }; every { record.state } returns AudioRecord.STATE_UNINITIALIZED
        assertTrue(failure().failure.cause.message!!.contains("initialize"))
        every { record.state } returns AudioRecord.STATE_INITIALIZED
        every { record.read(any<ByteArray>(), any(), any(), AudioRecord.READ_NON_BLOCKING) } returns -3
        assertEquals(-3, failure().failure.platformCode)
        val handle = FakeFileWriteHandle(-1).apply { writeFailure = IOException("private write detail") }
        every { record.read(any<ByteArray>(), any(), any(), AudioRecord.READ_NON_BLOCKING) } returns 0
        val write = adapter.capture(AudioCaptureRequest(AudioCaptureSource.MIC, true, 1_000,
            AudioSampleFile("private/audio-probe/sample.wav", handle))) as PortResult.Failure
        assertEquals("private write detail", write.failure.cause.message); assertTrue(handle.discarded)
        assertTrue(logger.entries.all { it.level == "error" && !it.cause!!.stackTraceToString().contains("private ") })
    }

    @Test fun invalidBufferReadAndCleanupFailuresNeverReturnASuccessfulSample() = runTest {
        val logger = FakeLogger(); val record = recorder(); var minimum = -2
        val adapter = AndroidAudioProbe(FakeClock(testScheduler), logger, StandardTestDispatcher(testScheduler), { minimum }, { _, _ -> record })
        suspend fun capture(): PortResult<AudioCaptureMeasurement> = adapter.capture(AudioCaptureRequest(AudioCaptureSource.MIC, false,
            1_000, AudioSampleFile("private/audio-probe/sample.wav", FakeFileWriteHandle(-1))))
        assertTrue((capture() as PortResult.Failure).failure.cause.message!!.contains("code -2"))
        minimum = 256
        every { record.read(any<ByteArray>(), any(), any(), AudioRecord.READ_NON_BLOCKING) } returns 1
        assertTrue((capture() as PortResult.Failure).failure.cause.message!!.contains("PCM16"))
        every { record.read(any<ByteArray>(), any(), any(), AudioRecord.READ_NON_BLOCKING) } returns 0
        every { record.stop() } throws IllegalStateException("stop rejected")
        every { record.release() } throws IllegalStateException("release rejected")
        val failed = capture() as PortResult.Failure
        assertEquals("stop rejected", failed.failure.cause.message)
        assertEquals("release rejected", failed.failure.cause.suppressed.single().message)
    }

    @Test fun cancellationDiscardsSampleAndStopsAndReleasesRecorder() = runTest {
        val record = recorder(); val handle = FakeFileWriteHandle(-1)
        every { record.read(any<ByteArray>(), any(), any(), AudioRecord.READ_NON_BLOCKING) } returns 0
        val adapter = AndroidAudioProbe(FakeClock(testScheduler), FakeLogger(), StandardTestDispatcher(testScheduler), { 256 }, { _, _ -> record })
        val job = launch { adapter.capture(AudioCaptureRequest(AudioCaptureSource.MIC, false, 1_000,
            AudioSampleFile("private/audio-probe/sample.wav", handle))) }
        runCurrent(); job.cancelAndJoin()
        assertTrue(handle.discarded); verify(exactly = 1) { record.stop(); record.release() }
    }
}
