package tech.atomiclabs.link.device.audio

import java.io.IOException
import org.junit.Assert.*
import org.junit.Test
import tech.atomiclabs.link.core.fakes.FakeLogger

class AudioCleanupTest {
    @Test fun preservesPrimaryAndSuppressedFailuresAndRedactsMessagesInErrorLogs() {
        val logger = FakeLogger(); val cleanup = AudioCleanup(logger, "AudioTest")
        val primary = IOException("private primary detail"); val release = IllegalStateException("private release detail")
        assertNull(cleanup.finish(null, "close") {})
        assertSame(primary, cleanup.finish(primary, "close") {})
        assertSame(primary, cleanup.finish(primary, "release") { throw release })
        assertSame(release, primary.suppressed.single())
        assertSame(release, cleanup.finish(null, "release") { throw release })
        assertSame(primary, cleanup.finish(primary, "same") { throw primary })
        assertEquals(3, logger.entries.size)
        assertTrue(logger.entries.all { it.level == "error" && it.cause != null && !it.cause.stackTraceToString().contains("private ") })
    }
}
