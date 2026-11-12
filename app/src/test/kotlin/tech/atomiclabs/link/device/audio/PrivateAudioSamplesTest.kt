package tech.atomiclabs.link.device.audio

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import tech.atomiclabs.link.core.audio.WavWriter
import tech.atomiclabs.link.core.fakes.FakeLogger
import tech.atomiclabs.link.core.port.*

class PrivateAudioSamplesTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun createsOnlyPrivateWavSamplesDeletesPreviousProbeAndSupportsCloseThenIdempotentDiscard() = runTest {
        val logger = FakeLogger(); val store = PrivateAudioSamples(temporary.root, logger, StandardTestDispatcher(testScheduler))
        assertEquals(PortResult.Success(Unit), store.clear())
        val sample = (store.create(AudioCaptureSource.MIC, true) as PortResult.Success).value
        val file = File(sample.path)
        assertEquals(File(temporary.root, "audio-probe").canonicalFile, file.canonicalFile.parentFile)
        assertTrue(file.name.endsWith(".wav")); assertEquals(-1L, sample.target.expectedSize)
        val header = WavWriter.header(0); sample.target.write(header, header.size); sample.target.close(); sample.target.close()
        assertArrayEquals(header, file.readBytes())
        assertTrue(store.create(AudioCaptureSource.MIC, true) is PortResult.Failure)
        assertEquals(PortResult.Success(Unit), store.clear()); assertFalse(file.exists())
        val next = (store.create(AudioCaptureSource.MIC, true) as PortResult.Success).value
        next.target.close(); next.target.discard(); next.target.discard()
        assertFalse(File(next.path).exists())
        assertThrows(IllegalStateException::class.java) { next.target.write(byteArrayOf(0, 0), 2) }
        assertEquals(1, logger.entries.size); assertEquals("error", logger.entries.single().level)
    }

    @Test fun unavailableOrNestedDirectoryIsALoudFailureAndDoesNotDeleteOutsideFiles() = runTest {
        val logger = FakeLogger(); val store = PrivateAudioSamples(temporary.root, logger, StandardTestDispatcher(testScheduler))
        val root = File(temporary.root, "audio-probe").apply { writeBytes(byteArrayOf(0)) }
        assertTrue(store.clear() is PortResult.Failure)
        assertTrue(store.create(AudioCaptureSource.MIC, false) is PortResult.Failure)
        assertTrue(root.delete()); assertTrue(root.mkdir()); assertTrue(File(root, "nested").mkdir())
        assertTrue(store.clear() is PortResult.Failure)
        assertTrue(logger.entries.all { it.level == "error" && it.cause != null })
    }

    @Test fun symlinkedSampleDirectoryCannotEscapeOrAliasAnotherCacheFolder() = runTest {
        val outside = temporary.newFolder("other-cache")
        val protected = File(outside, "keep").apply { writeBytes(byteArrayOf(1, 2)) }
        val root = File(temporary.root, "audio-probe")
        Files.createSymbolicLink(root.toPath(), outside.toPath())
        val store = PrivateAudioSamples(temporary.root, FakeLogger(), StandardTestDispatcher(testScheduler))
        assertTrue(store.clear() is PortResult.Failure)
        assertTrue(store.create(AudioCaptureSource.MIC, false) is PortResult.Failure)
        assertArrayEquals(byteArrayOf(1, 2), protected.readBytes())
    }
}
