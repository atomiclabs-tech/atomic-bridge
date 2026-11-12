package tech.atomiclabs.link.core.fakes

import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import tech.atomiclabs.link.core.port.*

class FakeFileAccessTest {
    @Test fun fileFailuresPreserveTheirPolicyReasonAndNeverReturnSuccess() = runTest {
        val fake = FakeFileAccess()
        val failure = FileResult.Failure(FileFailureKind.FORBIDDEN,
            PortFailure(FailureKind.REJECTED, IOException("fixture")))
        fake.failure = failure
        assertEquals(failure, fake.resolve("fixture"))
        assertEquals(failure, fake.open("fixture"))
        assertEquals(failure, fake.create("fixture", 2))
        assertEquals(failure, fake.rename("source", "target", false))
        assertEquals(listOf("resolve", "open", "create", "rename"), fake.calls)
    }

    @Test fun handlesStreamInChunksExposeFailuresAndDiscardPartialBytes() {
        val source = FakeFileReadHandle(byteArrayOf(1, 2, 3), chunkSize = 2)
        val target = FakeFileWriteHandle(3)
        val buffer = ByteArray(8)
        target.write(buffer.also { assertEquals(2, source.read(it)) }, 2)
        target.write(buffer.also { assertEquals(1, source.read(it)) }, 1)
        assertEquals(-1, source.read(buffer))
        assertArrayEquals(byteArrayOf(1, 2, 3), target.bytes)
        val failure = IOException("fixture")
        source.readFailure = failure
        try { source.read(buffer); fail("read must fail") } catch (actual: IOException) { assertSame(failure, actual) }
        target.discard()
        assertTrue(target.closed && target.discarded)
        assertEquals(0, target.bytes.size)
        source.close()
        assertTrue(source.closed)
    }
}
