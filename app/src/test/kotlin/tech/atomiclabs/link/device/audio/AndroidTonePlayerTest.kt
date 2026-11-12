package tech.atomiclabs.link.device.audio

import android.content.Context
import android.media.AudioTrack
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import tech.atomiclabs.link.core.fakes.*
import tech.atomiclabs.link.core.port.*

@OptIn(ExperimentalCoroutinesApi::class)
class AndroidTonePlayerTest {
    private fun track(): AudioTrack = mockk<AudioTrack>().apply {
        every { state } returns AudioTrack.STATE_INITIALIZED
        every { play() } just Runs; every { stop() } just Runs; every { release() } just Runs
        every { playState } returns AudioTrack.PLAYSTATE_PLAYING
        every { write(any<ByteArray>(), any(), any(), AudioTrack.WRITE_NON_BLOCKING) } answers { thirdArg<Int>() }
        every { playbackHeadPosition } returns 100_000
    }

    @Test fun playsDefaultToneThenSyntheticSpeechOnceOnEachRequestedStreamAndChecksCompletion() = runTest {
        val track = track(); val logger = FakeLogger(); val streams = mutableListOf<AudioPlaybackStream>()
        val writes = mutableListOf<Int>(); var clips = 0
        every { track.write(any<ByteArray>(), any(), any(), AudioTrack.WRITE_NON_BLOCKING) } answers {
            writes += thirdArg<Int>(); thirdArg<Int>()
        }
        val player = AndroidTonePlayer(mockk<Context>(), FakeClock(testScheduler), logger, StandardTestDispatcher(testScheduler),
            { streams += it; track }, { clips++; ByteArray(320) })
        for (stream in AudioPlaybackStream.entries) {
            assertEquals(PortResult.Success(AudioPlaybackMeasurement(stream, true, true)), player.play(AudioPlaybackRequest(stream, true)))
        }
        assertEquals(AudioPlaybackStream.entries, streams); assertEquals(listOf(48_320, 48_320), writes); assertEquals(2, clips)
        verify(exactly = 2) { track.play(); track.stop(); track.release() }
        assertTrue(logger.entries.isEmpty())
    }

    @Test fun invalidSpeechInitializationAndNegativeWriteReturnActualFailedMeasurementAndSanitizeLogs() = runTest {
        val track = track(); val logger = FakeLogger(); var clip = byteArrayOf()
        val player = AndroidTonePlayer(mockk<Context>(), FakeClock(testScheduler), logger, StandardTestDispatcher(testScheduler), { track }, { clip })
        val request = AudioPlaybackRequest(AudioPlaybackStream.USAGE_MEDIA, true)
        suspend fun failed(): AudioPlaybackMeasurement = (player.play(request) as PortResult.Success).value.also { assertFalse(it.played) }
        assertTrue(failed().error!!.contains("PCM16")); verify(exactly = 0) { track.play() }
        clip = ByteArray(320); every { track.state } returns AudioTrack.STATE_UNINITIALIZED
        assertTrue(failed().error!!.contains("initialize"))
        every { track.state } returns AudioTrack.STATE_INITIALIZED
        every { track.write(any<ByteArray>(), any(), any(), AudioTrack.WRITE_NON_BLOCKING) } returns -6
        assertTrue(failed().error!!.contains("code -6"))
        every { track.write(any<ByteArray>(), any(), any(), AudioTrack.WRITE_NON_BLOCKING) } throws SecurityException("private playback detail")
        assertEquals("private playback detail", failed().error)
        assertTrue(logger.entries.all { it.level == "error" && !it.cause!!.stackTraceToString().contains("private playback detail") })
    }

    @Test fun stalledWriteOrPlaybackHeadTimesOutAndStillStopsAndReleases() = runTest {
        val track = track(); val clock = FakeClock(testScheduler)
        val player = AndroidTonePlayer(mockk<Context>(), clock, FakeLogger(), StandardTestDispatcher(testScheduler), { track }, { ByteArray(320) })
        val request = AudioPlaybackRequest(AudioPlaybackStream.USAGE_VOICE_COMMUNICATION, true)
        every { track.write(any<ByteArray>(), any(), any(), AudioTrack.WRITE_NON_BLOCKING) } returns 0
        assertFalse((player.play(request) as PortResult.Success).value.played)
        assertEquals(15_000L, clock.monotonicMs())
        every { track.write(any<ByteArray>(), any(), any(), AudioTrack.WRITE_NON_BLOCKING) } answers { thirdArg<Int>() }
        every { track.playbackHeadPosition } returns 0
        assertFalse((player.play(request) as PortResult.Success).value.played)
        assertEquals(30_000L, clock.monotonicMs())
        verify(exactly = 2) { track.stop(); track.release() }
    }

    @Test fun cleanupFailurePreventsPlayedSuccessAndCancellationIsNotReportedAsPlayback() = runTest {
        val track = track()
        val player = AndroidTonePlayer(mockk<Context>(), FakeClock(testScheduler), FakeLogger(), StandardTestDispatcher(testScheduler),
            { track }, { ByteArray(320) })
        every { track.stop() } throws IllegalStateException("stop rejected")
        val request = AudioPlaybackRequest(AudioPlaybackStream.USAGE_MEDIA, true)
        val result = (player.play(request) as PortResult.Success).value
        assertFalse(result.played); assertEquals("stop rejected", result.error)
        every { track.stop() } just Runs
        every { track.write(any<ByteArray>(), any(), any(), AudioTrack.WRITE_NON_BLOCKING) } returns 0
        val job = launch { player.play(request) }
        runCurrent(); job.cancelAndJoin(); assertTrue(job.isCancelled)
        verify(exactly = 2) { track.stop(); track.release() }
    }
}
