package tech.atomiclabs.link.net

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.mockwebserver.*
import okio.Buffer
import okio.ByteString.Companion.toByteString
import org.junit.Assert.*
import org.junit.Test
import tech.atomiclabs.link.core.fakes.FakeLogger
import tech.atomiclabs.link.core.fakes.FakeFileReadHandle
import tech.atomiclabs.link.core.fakes.FakeFileWriteHandle
import tech.atomiclabs.link.core.port.*

class RelayMediaClientTest {
    @Test fun fileUploadStreamsManyChunksAndReturnsTheChecksumOfOnlyTheFileBytes() = runBlocking {
        MediaFixture().use { h ->
            val bytes = ByteArray(32_779) { (it % 251).toByte() }
            val source = FakeFileReadHandle(bytes, chunkSize = 37)
            h.server.enqueue(MockResponse().setBody("""{"ok":true,"token":"file-fixture"}"""))
            val result = h.media.uploadFile(h.base, source, "application/octet-stream", "fixture.bin") as PortResult.Success
            assertEquals(bytes.size.toLong(), result.value.info.size)
            assertEquals(sha256(bytes), result.value.info.sha256)
            assertEquals("application/octet-stream", result.value.info.contentType)
            assertTrue(source.closed && source.reads > 800)
            val request = h.request()
            assertEquals("POST /media/upload HTTP/1.1", request.requestLine)
            assertTrue(request.getHeader("Authorization") == "Bearer $sessionFixture")
            assertTrue(request.body.indexOf(bytes.toByteString()) >= 0)
            assertEquals(listOf(60_000L), h.timeouts.toList())
            assertPrivate(h.logger, "fixture.bin", "file-fixture", sessionFixture)
        }
    }

    @Test fun fileDownloadHashesMultipleNetworkChunksAndClosesTheCompletedTarget() = runBlocking {
        MediaFixture().use { h ->
            val bytes = ByteArray(24_613) { (it % 239).toByte() }
            val target = FakeFileWriteHandle(bytes.size.toLong())
            h.server.enqueue(MockResponse().setChunkedBody(Buffer().write(bytes), 67).setHeader("Content-Type", "application/octet-stream"))
            val result = h.media.fetchToFile(h.base, "MEDIA:hermes-relay://file-fixture", target, RelayMediaClient.MAX_STREAM_BYTES) as PortResult.Success
            assertArrayEquals(bytes, target.bytes)
            assertTrue(target.closed && !target.discarded && target.writes > 1)
            assertEquals(sha256(bytes), result.value.sha256)
            assertEquals(bytes.size.toLong(), result.value.size)
            assertEquals("GET /media/file-fixture HTTP/1.1", h.request().requestLine)
            assertEquals(listOf(60_000L), h.timeouts.toList())
            assertPrivate(h.logger, "file-fixture", sessionFixture)
        }
    }

    @Test fun fileTransfersRefuseAbove256MiBWithoutAllocatingOrReadingAnOversizedFile() = runBlocking {
        MediaFixture().use { h ->
            val source = FakeFileReadHandle(byteArrayOf(), RelayMediaClient.MAX_STREAM_BYTES + 1)
            val upload = h.media.uploadFile(h.base, source, "application/octet-stream", "fixture.bin") as PortResult.Failure
            assertEquals(FailureKind.INVALID_DATA, upload.failure.kind)
            assertEquals(0, source.reads)
            assertTrue(source.closed)
            assertEquals(0, h.server.requestCount)
            val target = FakeFileWriteHandle(0)
            h.server.enqueue(MockResponse().setBody("").setHeader("Content-Length", RelayMediaClient.MAX_STREAM_BYTES + 1))
            val download = h.media.fetchToFile(h.base, "file-fixture", target, RelayMediaClient.MAX_STREAM_BYTES) as PortResult.Failure
            assertEquals(FailureKind.INVALID_DATA, download.failure.kind)
            assertEquals(0, target.writes)
            assertTrue(target.discarded)
            h.request()
            Unit
        }
    }

    @Test fun chunkedDownloadCannotExceedCallerLimitEvenWithoutContentLength() = runBlocking {
        MediaFixture().use { h ->
            val target = FakeFileWriteHandle(64)
            h.server.enqueue(MockResponse().setChunkedBody(Buffer().write(ByteArray(65)), 7))
            val result = h.media.fetchToFile(h.base, "file-fixture", target, 64) as PortResult.Failure
            assertEquals(FailureKind.INVALID_DATA, result.failure.kind)
            assertTrue(target.discarded)
            assertEquals(0, target.bytes.size)
            h.request()
            Unit
        }
    }

    @Test fun midStreamDisconnectDeletesThePartialTargetFileAndDoesNotRetry() = runBlocking {
        MediaFixture().use { h ->
            val target = DiskTarget(65_536)
            try {
                h.server.enqueue(MockResponse().setBody(Buffer().write(ByteArray(65_536)))
                    .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
                val result = h.media.fetchToFile(h.base, "file-fixture", target, RelayMediaClient.MAX_STREAM_BYTES) as PortResult.Failure
                assertEquals(FailureKind.IO, result.failure.kind)
                assertTrue(target.writes > 0)
                assertFalse(Files.exists(target.path))
                h.request()
                assertEquals(1, h.server.requestCount)
                assertPrivate(h.logger, target.path.toString(), "file-fixture", sessionFixture)
            } finally { target.cleanup() }
        }
    }

    @Test fun fileTimeoutHas60SecondFloorAndOneSecondPerStartedMiB() = runBlocking {
        MediaFixture().use { h ->
            assertEquals(60_000L, h.media.fileTimeoutMs(0))
            assertEquals(60_000L, h.media.fileTimeoutMs(60L * 1024 * 1024))
            assertEquals(61_000L, h.media.fileTimeoutMs(60L * 1024 * 1024 + 1))
            assertEquals(256_000L, h.media.fileTimeoutMs(RelayMediaClient.MAX_STREAM_BYTES))
        }
    }

    @Test fun fileDownloadTimeoutIsTypedAndDeletesTheTargetWithoutRetry() = runBlocking {
        MediaFixture(fileMinMs = 100, filePerMiBMs = 1).use { h ->
            val target = DiskTarget(1)
            try {
                h.server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
                val result = h.media.fetchToFile(h.base, "file-fixture", target, 1) as PortResult.Failure
                assertEquals(FailureKind.TIMED_OUT, result.failure.kind)
                assertFalse(Files.exists(target.path))
                h.request()
                assertEquals(1, h.server.requestCount)
                assertEquals(listOf(100L), h.timeouts.toList())
            } finally { target.cleanup() }
        }
    }

    @Test fun cancellationDiscardsTheTargetAndCancelsTheSingleFileRequest() = runBlocking {
        MediaFixture().use { h ->
            val target = FakeFileWriteHandle(1)
            h.server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val pending = launch { h.media.fetchToFile(h.base, "file-fixture", target, 1) }
            withContext(Dispatchers.IO) { h.request() }
            pending.cancelAndJoin()
            assertTrue(target.closed && target.discarded)
            withTimeout(5_000) { while (h.client.dispatcher.runningCallsCount() != 0) yield() }
            assertEquals(1, h.server.requestCount)
        }
    }

    @Test fun downloadWriteAndCleanupFailuresAreReturnedWithSanitizedStackTraces() = runBlocking {
        MediaFixture().use { h ->
            val target = FakeFileWriteHandle(1).apply {
                writeFailure = java.io.IOException("private-file-data")
                discardFailure = java.io.IOException("private-file-path")
            }
            h.server.enqueue(MockResponse().setBody(Buffer().write(byteArrayOf(1))))
            val result = h.media.fetchToFile(h.base, "file-fixture", target, 1) as PortResult.Failure
            assertEquals(FailureKind.IO, result.failure.kind)
            assertTrue(h.logger.entries.any { it.operation == "fetchToFile" && it.cause != null })
            assertPrivate(h.logger, "private-file-data", "private-file-path", "file-fixture", sessionFixture)
            h.request()
            Unit
        }
    }

    @Test fun uploadsPngAndJpegAsFileMultipartWithFreshBearerAndProperJsonTokenParsing() = runBlocking {
        MediaFixture().use { h ->
            for (format in ImageFormat.entries) {
                val token = "media-\"quoted-${format.name}"
                h.server.enqueue(MockResponse().setBody("""{"ok":true,"nested":{"token":"wrong"},"token":"${token.replace("\"", "\\\"")}"}"""))
                val credential = "session-fixture-${format.name}"
                h.credential = ReadValue.Present(credential)
                val result = h.media.upload(h.base, EncodedImage(1, 1, format, "image-fixture".toByteArray())) as PortResult.Success
                assertTrue(result.value.token == token)
                val request = h.request()
                assertEquals("POST /media/upload HTTP/1.1", request.requestLine)
                assertTrue(request.getHeader("Authorization") == "Bearer $credential")
                assertFalse(request.requestLine.contains(credential))
                assertTrue(request.getHeader("Content-Type")!!.startsWith("multipart/form-data;"))
                val body = request.body.readUtf8()
                assertTrue(body.contains("name=\"file\""))
                assertTrue(body.contains("Content-Type: ${if (format == ImageFormat.PNG) "image/png" else "image/jpeg"}"))
                assertTrue(body.contains("image-fixture"))
                assertPrivate(h.logger, credential, token)
            }
            assertEquals(listOf(15_000L, 15_000L), h.timeouts.toList())
        }
    }

    @Test fun uploadHttpFailuresRetainStatusAndNeverExposeResponseDiagnostics() = runBlocking {
        MediaFixture().use { h ->
            for (status in listOf(401, 413)) {
                h.server.enqueue(MockResponse().setResponseCode(status).setBody("private-http-body"))
                val result = h.media.upload(h.base, image()) as PortResult.Failure
                assertEquals(FailureKind.REJECTED, result.failure.kind)
                assertEquals(status, result.failure.httpStatus)
                assertFalse(result.failure.cause.stackTraceToString().contains("private-http-body"))
                h.request()
            }
            assertEquals(2, h.server.requestCount)
            assertPrivate(h.logger, "private-http-body", sessionFixture)
        }
    }

    @Test fun uploadRejectsMissingTokenWrongOkAndMalformedJsonInsteadOfFakeSuccess() = runBlocking {
        MediaFixture().use { h ->
            for (body in listOf("""{"ok":true}""", """{"ok":false,"token":"private-result"}""",
                """{"ok":true,"token":12}""", "{private-json-body")) {
                h.server.enqueue(MockResponse().setBody(body))
                val result = h.media.upload(h.base, image()) as PortResult.Failure
                assertEquals(FailureKind.INVALID_DATA, result.failure.kind)
                assertEquals(200, result.failure.httpStatus)
                assertNotNull(result.failure.cause)
                h.request()
            }
            assertPrivate(h.logger, "private-result", "private-json-body", sessionFixture)
        }
    }

    @Test fun unansweredUploadTimesOutWithoutRetryingTheHttpSend() = runBlocking {
        MediaFixture(uploadMs = 100).use { h ->
            h.server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val result = h.media.upload(h.base, image()) as PortResult.Failure
            assertEquals(FailureKind.TIMED_OUT, result.failure.kind)
            assertNull(result.failure.httpStatus)
            assertNotNull(result.failure.cause)
            h.request()
            assertEquals(1, h.server.requestCount)
            assertEquals(listOf(100L), h.timeouts.toList())
            assertPrivate(h.logger, sessionFixture)
        }
    }

    @Test fun fetchReturnsBytesAndResponseContentTypeForBareAndMarkedMediaTokens() = runBlocking {
        MediaFixture().use { h ->
            val bytes = byteArrayOf(0, 1, 2, -1)
            for (token in listOf("asset-fixture", "MEDIA:hermes-relay://asset-fixture")) {
                h.server.enqueue(MockResponse().setBody(Buffer().write(bytes)).setHeader("Content-Type", "image/png"))
                val result = h.media.fetch(h.base, token) as PortResult.Success
                assertArrayEquals(bytes, result.value.bytes)
                assertEquals("image/png", result.value.contentType)
                val request = h.request()
                assertEquals("GET /media/asset-fixture HTTP/1.1", request.requestLine)
                assertTrue(request.getHeader("Authorization") == "Bearer $sessionFixture")
                assertFalse(request.requestLine.contains(sessionFixture))
            }
            assertEquals(listOf(20_000L, 20_000L), h.timeouts.toList())
            assertPrivate(h.logger, sessionFixture, "asset-fixture")
        }
    }

    @Test fun fetchCapsBothDeclaredAndChunkedBodiesAtTwentyFiveMiB() = runBlocking {
        MediaFixture().use { h ->
            h.server.enqueue(MockResponse().setBody("").setHeader("Content-Length", RelayMediaClient.MAX_FILE_BYTES + 1))
            h.server.enqueue(MockResponse().setChunkedBody(
                Buffer().write(ByteArray((RelayMediaClient.MAX_FILE_BYTES + 1).toInt())), 8_192))
            repeat(2) {
                val result = h.media.fetch(h.base, "asset-fixture") as PortResult.Failure
                assertEquals(FailureKind.INVALID_DATA, result.failure.kind)
                assertEquals(200, result.failure.httpStatus)
                h.request()
            }
            assertPrivate(h.logger, sessionFixture, "asset-fixture")
        }
    }

    @Test fun fetch404AndRedirectsAreTypedFailuresAndAreNotFollowedOrReplayed() = runBlocking {
        MediaFixture().use { h ->
            h.server.enqueue(MockResponse().setResponseCode(404).setBody("private-not-found"))
            h.server.enqueue(MockResponse().setResponseCode(307).setHeader("Location", h.base + "/other"))
            for (status in listOf(404, 307)) {
                val result = h.media.fetch(h.base, "asset-fixture") as PortResult.Failure
                assertEquals(FailureKind.REJECTED, result.failure.kind)
                assertEquals(status, result.failure.httpStatus)
                h.request()
            }
            assertEquals(2, h.server.requestCount)
            assertPrivate(h.logger, "private-not-found", sessionFixture, "asset-fixture")
        }
    }

    @Test fun missingUnreadableOrMalformedCredentialsFailBeforeAnyHttpRequest() = runBlocking {
        MediaFixture().use { h ->
            val cause = IllegalStateException("private-token-read")
            for ((credential, kind) in listOf(
                ReadValue.Absent to FailureKind.UNAVAILABLE,
                ReadValue.Unreadable(PortFailure(FailureKind.IO, cause)) to FailureKind.IO,
                ReadValue.Present("private-token\n") to FailureKind.INVALID_DATA)) {
                h.credential = credential
                val result = h.media.upload(h.base, image()) as PortResult.Failure
                assertEquals(kind, result.failure.kind)
            }
            assertEquals(0, h.server.requestCount)
            assertPrivate(h.logger, "private-token-read", "private-token", sessionFixture)
        }
    }

    @Test fun uploadAndFetch503KeepTheFirstStatusWithoutAnAutomaticSecondSend() = runBlocking {
        for (upload in listOf(true, false)) MediaFixture().use { h ->
            h.server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "0").setBody("private-retry-body"))
            h.server.enqueue(MockResponse().setBody("""{"ok":true,"token":"unexpected-replay"}"""))
            val result = if (upload) h.media.upload(h.base, image()) else h.media.fetch(h.base, "asset-fixture")
            val failure = (result as PortResult.Failure).failure
            assertEquals(503, failure.httpStatus)
            assertEquals(FailureKind.REJECTED, failure.kind)
            h.request()
            assertEquals(1, h.server.requestCount)
            assertPrivate(h.logger, "private-retry-body", sessionFixture)
        }
    }

    @Test fun cancellationCancelsTheInFlightCallWithoutASecondRequest() = runBlocking {
        MediaFixture().use { h ->
            h.server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val pending = launch { h.media.fetch(h.base, "asset-fixture") }
            withContext(Dispatchers.IO) { h.request() }
            pending.cancelAndJoin()
            withTimeout(5_000) { while (h.client.dispatcher.runningCallsCount() != 0) yield() }
            assertEquals(1, h.server.requestCount)
            assertPrivate(h.logger, sessionFixture, "asset-fixture")
        }
    }

    private fun image() = EncodedImage(1, 1, ImageFormat.PNG, byteArrayOf(1, 2, 3))
}

private const val sessionFixture = "media-session-fixture"

private class MediaFixture(uploadMs: Long = 15_000, fetchMs: Long = 20_000,
    fileMinMs: Long = 60_000, filePerMiBMs: Long = 1_000) : AutoCloseable {
    val server = MockWebServer().apply { start() }
    val base = server.url("/").toString()
    val logger = FakeLogger()
    val timeouts = ConcurrentLinkedQueue<Long>()
    val client = createRelayClient().newBuilder().eventListener(object : EventListener() {
        override fun callStart(call: Call) { timeouts += TimeUnit.NANOSECONDS.toMillis(call.timeout().timeoutNanos()) }
    }).build()
    var credential: ReadValue<String> = ReadValue.Present(sessionFixture)
    val media = RelayMediaClient(client, SessionTokenProvider { credential }, logger, uploadMs, fetchMs, fileMinMs, filePerMiBMs)
    fun request(): RecordedRequest = checkNotNull(server.takeRequest(2, TimeUnit.SECONDS))
    override fun close() { shutdown(client); server.shutdown() }
}

private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
    .joinToString("") { "%02x".format(it.toInt() and 0xff) }

private class DiskTarget(override val expectedSize: Long) : FileWriteHandle {
    private val directory = Files.createTempDirectory("atomic-file-test")
    val path: Path = directory.resolve("partial-fixture")
    private val output = Files.newOutputStream(path)
    var writes = 0
    override fun write(buffer: ByteArray, count: Int) { writes++; output.write(buffer, 0, count) }
    override fun close() { output.close() }
    override fun discard() { close(); Files.deleteIfExists(path) }
    fun cleanup() { discard(); Files.delete(directory) }
}
