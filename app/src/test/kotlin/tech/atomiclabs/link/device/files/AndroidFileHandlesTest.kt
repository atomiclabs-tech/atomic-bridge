package tech.atomiclabs.link.device.files

import java.io.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import tech.atomiclabs.link.core.fakes.FakeLogger

class AndroidFileHandlesTest {
    @get:Rule val temporary = TemporaryFolder()
    private val logger = FakeLogger()
    @Test fun readerUsesChunksAndCloseIsIdempotent() {
        val source = AndroidFileReadHandle(ByteArrayInputStream(byteArrayOf(1, 2, 3)), 3, logger)
        val buffer = ByteArray(2)
        assertEquals(3, source.size)
        assertEquals(2, source.read(buffer)); assertArrayEquals(byteArrayOf(1, 2), buffer)
        assertEquals(1, source.read(buffer)); assertEquals(-1, source.read(buffer))
        source.close(); source.close()
        try { source.read(buffer); fail("Closed reads must fail") } catch (expected: IOException) {
            assertEquals("The file source is closed.", expected.message)
        }
        assertEquals("read", logger.entries.single().operation)
    }
    @Test fun writerEnforcesExpectedSizeAndDiscardAfterCloseDeletesOnlyItsPart() {
        val part = temporary.newFile("fixture.part")
        val other = temporary.newFile("untouched")
        val target = AndroidFileWriteHandle(part.outputStream(), part, 3, logger) {}
        target.write(byteArrayOf(1, 2), 2)
        try { target.write(byteArrayOf(3, 4), 2); fail("Oversize writes must fail") } catch (expected: IOException) {
            assertEquals("The file exceeds its expected size.", expected.message)
        }
        target.write(byteArrayOf(3), 1); target.close(); target.close()
        assertArrayEquals(byteArrayOf(1, 2, 3), part.readBytes())
        target.discard(); target.discard()
        assertFalse(part.exists()); assertTrue(other.exists())
        try { target.write(byteArrayOf(4), 1); fail("Discarded writes must fail") } catch (expected: IOException) {
            assertEquals("The temporary file is closed.", expected.message)
        }
    }
    @Test fun readWriteAndCloseFailuresRetainCauseAndRedactSensitiveMessages() {
        val cause = IOException("private-file-name")
        val reader = AndroidFileReadHandle(object : InputStream() {
            override fun read(): Int = throw cause
            override fun close(): Unit = throw cause
        }, 3, logger)
        val part = temporary.newFile("fixture.part")
        val writer = AndroidFileWriteHandle(object : OutputStream() {
            override fun write(value: Int): Unit = throw cause
            override fun close(): Unit = throw cause
        }, part, 3, logger) {}
        for (operation in listOf<() -> Unit>({ reader.read(ByteArray(1)) }, { reader.close() },
            { writer.write(byteArrayOf(1), 1) }, { writer.discard() })) {
            try { operation(); fail("The failure must reach the caller") } catch (expected: IOException) { assertSame(cause, expected) }
        }
        assertFalse(part.exists())
        assertTrue(logger.entries.all { it.level == "error" && it.cause!!.stackTrace.contentEquals(cause.stackTrace) })
        assertFalse(logger.entries.joinToString { it.cause!!.stackTraceToString() }.contains("private-file-name"))
    }
}
