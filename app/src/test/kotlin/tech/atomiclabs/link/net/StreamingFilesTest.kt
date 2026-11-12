package tech.atomiclabs.link.net

import java.io.IOException
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import tech.atomiclabs.link.core.fakes.*

class StreamingFilesTest {
    @Test fun uploadIsOneShotAndClosedSourceCannotBeReadByLateCallback() {
        val source = FakeFileReadHandle(byteArrayOf(1, 2, 3), chunkSize = 1)
        val body = FileUploadBody(source, "application/octet-stream")
        assertTrue(body.isOneShot())
        body.close()
        try { body.writeTo(Buffer()); fail("closed source must refuse") }
        catch (expected: IOException) { assertEquals("The upload source is closed.", expected.message) }
        assertTrue(source.closed)
        assertEquals(0, source.reads)
    }

    @Test fun abortedTargetCannotBeRecreatedByLateResponseCallback() {
        val target = FakeFileWriteHandle(3)
        val download = FileDownload(target, 3)
        download.abort()
        try { download.copy(byteArrayOf(1, 2, 3).toResponseBody()); fail("aborted target must refuse") }
        catch (expected: IOException) { assertEquals("The download target is closed.", expected.message) }
        assertEquals(0, target.writes)
        assertTrue(target.discarded)
    }
}
