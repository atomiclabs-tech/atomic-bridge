package tech.atomiclabs.link.core.port

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import tech.atomiclabs.link.core.fakes.*

class CapturePortsTest {
    @Test fun legacyMediaImplementationsRefuseNewOperationsAndReleaseHandles() = runTest {
        val media = object : MediaTransfer {
            override suspend fun upload(httpBase: String, image: EncodedImage): PortResult<UploadedMedia> = error("unused")
            override suspend fun fetch(httpBase: String, token: String): PortResult<FetchedMedia> = error("unused")
        }
        val source = FakeFileReadHandle(byteArrayOf(1))
        val target = FakeFileWriteHandle(1)
        val upload = media.uploadFile("https://relay.example", source, "text/plain", "fixture") as PortResult.Failure
        val download = media.fetchToFile("https://relay.example", "fixture", target, 1) as PortResult.Failure
        assertEquals(FailureKind.UNAVAILABLE, upload.failure.kind)
        assertEquals(FailureKind.UNAVAILABLE, download.failure.kind)
        assertTrue(source.closed)
        assertTrue(target.closed && target.discarded)
    }
}
