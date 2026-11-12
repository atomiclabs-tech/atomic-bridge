package tech.atomiclabs.link.core.files

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import tech.atomiclabs.link.core.command.*
import tech.atomiclabs.link.core.port.*

@OptIn(ExperimentalCoroutinesApi::class)
class FilePullTest {
    @Test fun pullUsesStreamedUploadAndReturnsActualMetadataAndExtensionType() = runTest {
        val f = FilesFixture()
        val result = f.call("/files/pull", """{"path":"Download/source.jpg"}""").result
        assertEquals(JsonPrimitive(true), result["success"])
        assertEquals(JsonPrimitive("MEDIA:hermes-relay://uploaded-fixture"), result["media"])
        assertEquals(JsonPrimitive("source.jpg"), result["name"])
        assertEquals(JsonPrimitive(3), result["size"])
        assertEquals(JsonPrimitive(digest(byteArrayOf(1, 2, 3))), result["sha256"])
        assertEquals(JsonPrimitive("image/jpeg"), result["content_type"])
        assertEquals(listOf(Triple(f.endpoint, "image/jpeg", "source.jpg")), f.media.uploads)
        assertEquals(4, f.files.reader.reads); assertTrue(f.files.reader.closed)
        assertTrue(f.logger.entries.isEmpty())
    }
    @Test fun extensionTableIncludesAllApprovedTypesAndUnknownUsesOctetStream() {
        for ((extensions, type) in listOf("jpg jpeg" to "image/jpeg", "png" to "image/png", "gif" to "image/gif",
            "webp" to "image/webp", "mp4" to "video/mp4", "mov" to "video/quicktime", "mp3" to "audio/mpeg",
            "m4a" to "audio/mp4", "pdf" to "application/pdf", "txt" to "text/plain", "json" to "application/json",
            "zip" to "application/zip", "unknown" to "application/octet-stream")) {
            extensions.split(' ').forEach { assertEquals(type, FilePull.contentType("photo.${it.uppercase()}")) }
        }
        assertEquals("application/octet-stream", FilePull.contentType("no-extension"))
    }
    @Test fun uploadHttpFailureIsVisibleAndSourceIsClosedWithoutPrivateDetails() = runTest {
        val f = FilesFixture()
        f.media.uploadFailure = PortFailure(FailureKind.REJECTED, java.io.IOException("private-file-and-token"), httpStatus = 413)
        val response = f.call("/files/pull", """{"path":"Download/source.jpg"}""")
        failure(response, ErrorCode.UPLOAD_FAILED)
        assertTrue(response.result["error"]!!.jsonPrimitive.content.contains("413"))
        assertTrue(f.files.reader.closed)
        assertFalse(response.result.toString().contains("private-file-and-token"))
        assertFalse(f.logger.entries.single().cause!!.stackTraceToString().contains("private-file-and-token"))
        f.media.uploadFailure = PortFailure(FailureKind.IO, java.io.IOException("private-file-and-token"))
        val ioFailure = f.call("/files/pull", """{"path":"Download/source.jpg"}""")
        failure(ioFailure, ErrorCode.UPLOAD_FAILED)
        assertEquals(JsonPrimitive("The file upload failed with io."), ioFailure.result["error"])
    }
    @Test fun directoryAndMissingEndpointDoNotOpenOrUpload() = runTest {
        val f = FilesFixture()
        failure(f.call("/files/pull", """{"path":"Download"}"""), ErrorCode.BAD_REQUEST)
        f.endpoint = ""
        failure(f.call("/files/pull", """{"path":"Download/source.jpg"}"""), ErrorCode.UPLOAD_FAILED)
        assertFalse(f.files.calls.any { it.first == "open" }); assertTrue(f.media.uploads.isEmpty())
    }
    @Test fun invalidUploadMetadataIsNotReturnedAsSuccess() = runTest {
        val f = FilesFixture()
        f.media.info = FileTransferInfo(2, "invalid", null)
        failure(f.call("/files/pull", """{"path":"Download/source.jpg"}"""), ErrorCode.UPLOAD_FAILED)
        assertTrue(f.files.reader.closed)
    }
    @Test fun featureAllowsLongMediaBudgetAndClosesStreamAtItsOwnBound() = runTest {
        val f = FilesFixture()
        val route = f.group.routes.single { it.path == "/files/pull" }
        val body = BodyParams(Json.parseToJsonElement("""{"path":"Download/source.jpg"}""").jsonObject)
        f.media.beforeUpload = { delay(60_000) }
        val success = async { route.handle(body) }
        advanceUntilIdle()
        assertTrue(success.await() is CommandResult.Success)
        assertEquals(60_000, currentTime)
        f.files.reader = tech.atomiclabs.link.core.fakes.FakeFileReadHandle(byteArrayOf(1, 2, 3))
        f.media.beforeUpload = { awaitCancellation() }
        val timeout = async { route.handle(body) }
        advanceUntilIdle()
        assertEquals(ErrorCode.UPLOAD_FAILED, (timeout.await() as CommandResult.Failure).code)
        assertEquals(330_000, currentTime)
        assertTrue(f.files.reader.closed)
    }
}
