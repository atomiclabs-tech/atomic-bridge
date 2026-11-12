package tech.atomiclabs.link.device.access

import android.media.AudioManager
import io.mockk.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import tech.atomiclabs.link.core.fakes.FakeLogger
import tech.atomiclabs.link.core.port.*

class AndroidVolumeControlTest {
    @Test fun bothDirectionsAdjustOnlyMusicWithNoUiFlag() = runTest {
        val audio = mockk<AudioManager>()
        every { audio.adjustStreamVolume(any(), any(), any()) } just Runs
        val adapter = AndroidVolumeControl(audio, FakeLogger(), StandardTestDispatcher(testScheduler))
        assertEquals(PortResult.Success(Unit), adapter.adjust(VolumeDirection.UP))
        assertEquals(PortResult.Success(Unit), adapter.adjust(VolumeDirection.DOWN))
        verify(exactly = 1) { audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, 0) }
        verify(exactly = 1) { audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, 0) }
    }

    @Test fun permissionFailureRetainsCauseAndLogsNoPrivateMessage() = runTest {
        val audio = mockk<AudioManager>(); val logger = FakeLogger()
        val cause = SecurityException("private fixture payload")
        every { audio.adjustStreamVolume(any(), any(), any()) } throws cause
        val failed = AndroidVolumeControl(audio, logger, StandardTestDispatcher(testScheduler)).adjust(VolumeDirection.UP) as PortResult.Failure
        assertEquals(FailureKind.PERMISSION_DENIED, failed.failure.kind)
        assertTrue(generateSequence(failed.failure.cause) { it.cause }.any { it === cause })
        assertEquals("error", logger.entries.single().level)
        assertFalse(logger.entries.single().cause!!.stackTraceToString().contains("private fixture payload"))
    }
}
