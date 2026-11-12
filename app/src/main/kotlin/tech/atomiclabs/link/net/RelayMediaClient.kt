// Ported from upstream app/src/main/kotlin/com/hermesandroid/relay/accessibility/ScreenCapture.kt at commit da782a8.
package tech.atomiclabs.link.net

import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer
import tech.atomiclabs.link.core.command.FailureDiagnostics
import tech.atomiclabs.link.core.connection.RelayUrl
import tech.atomiclabs.link.core.log.Logger
import tech.atomiclabs.link.core.port.*

class RelayMediaClient(
    private val client: OkHttpClient,
    private val sessions: SessionTokenProvider,
    private val logger: Logger,
    private val uploadTimeoutMs: Long = 15_000,
    private val fetchTimeoutMs: Long = 20_000,
    private val fileMinimumTimeoutMs: Long = 60_000,
    private val fileMillisPerMiB: Long = 1_000,
) : MediaTransfer {
    override suspend fun upload(httpBase: String, image: EncodedImage): PortResult<UploadedMedia> =
        try { withTimeout(uploadTimeoutMs) {
        transfer("upload", httpBase, uploadTimeoutMs, { base, credential ->
            val contentType = if (image.format == ImageFormat.PNG) "image/png" else "image/jpeg"
            val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("file", "capture.${if (image.format == ImageFormat.PNG) "png" else "jpg"}",
                    image.bytes.toRequestBody(contentType.toMediaType())).build()
            Request.Builder().url(base.newBuilder().addPathSegments("media/upload").build())
                .header("Authorization", "Bearer $credential").header("Accept", "application/json").post(body).singleAttempt()
        }) { response ->
            val json = Json.parseToJsonElement(response.body.string()).jsonObject
            require((json["ok"] as? JsonPrimitive)?.booleanOrNull == true) { "The upload was not accepted." }
            val token = (json["token"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            require(!token.isNullOrBlank()) { "The upload response has no media token." }
            UploadedMedia(token)
        }
        } } catch (cause: TimeoutCancellationException) { failure("upload", cause, FailureKind.TIMED_OUT) }

    override suspend fun fetch(httpBase: String, token: String): PortResult<FetchedMedia> {
        val timeoutMs = fetchTimeoutMs
        return try { withTimeout(timeoutMs) {
        transfer("fetch", httpBase, timeoutMs, { base, credential ->
            val media = token.removePrefix("MEDIA:hermes-relay://")
            require(media.isNotBlank() && media != "." && media != "..") { "The media token is invalid." }
            Request.Builder().url(base.newBuilder().addPathSegment("media").addPathSegment(media).build())
                .header("Authorization", "Bearer $credential").singleAttempt()
        }) { response ->
            require(response.body.contentLength() <= MAX_FILE_BYTES) { "The media file exceeds 25 MiB." }
            val buffer = Buffer()
            val source = response.body.source()
            var size = 0L
            while (true) {
                val read = source.read(buffer, minOf(8_192L, MAX_FILE_BYTES - size + 1))
                if (read == -1L) break
                size += read
                require(size <= MAX_FILE_BYTES) { "The media file exceeds 25 MiB." }
            }
            FetchedMedia(buffer.readByteArray(), response.header("Content-Type"))
        }
        } } catch (cause: TimeoutCancellationException) { failure("fetch", cause, FailureKind.TIMED_OUT) }
    }

    override suspend fun uploadFile(httpBase: String, source: FileReadHandle, contentType: String,
        name: String): PortResult<UploadedFile> = try {
        withContext(Dispatchers.IO) {
            FileUploadBody(source, contentType).use { body ->
                require(source.size in 0..MAX_STREAM_BYTES) { "The file exceeds 256 MiB." }
                require(name.isNotBlank() && name.none { it == '/' || it == '\\' || it == '\r' || it == '\n' }) {
                    "The upload name must be a plain file name."
                }
                val timeoutMs = fileTimeoutMs(source.size)
                withTimeout(timeoutMs) {
                    transfer("uploadFile", httpBase, timeoutMs, { base, credential ->
                        val multipart = MultipartBody.Builder().setType(MultipartBody.FORM)
                            .addFormDataPart("file", name, body).build()
                        Request.Builder().url(base.newBuilder().addPathSegments("media/upload").build())
                            .header("Authorization", "Bearer $credential").header("Accept", "application/json")
                            .post(multipart).singleAttempt()
                    }) { response ->
                        val json = Json.parseToJsonElement(response.body.string()).jsonObject
                        require((json["ok"] as? JsonPrimitive)?.booleanOrNull == true) { "The upload was not accepted." }
                        val token = (json["token"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                        require(!token.isNullOrBlank()) { "The upload response has no media token." }
                        UploadedFile(token, body.info())
                    }
                }
            }
        }
    } catch (cause: TimeoutCancellationException) { failure("uploadFile", cause, FailureKind.TIMED_OUT) }
      catch (cause: CancellationException) { throw cause }
      catch (cause: Exception) { failure("uploadFile", cause,
          if (cause is IllegalArgumentException) FailureKind.INVALID_DATA else FailureKind.IO) }

    override suspend fun fetchToFile(httpBase: String, token: String, target: FileWriteHandle,
        maxBytes: Long): PortResult<FileTransferInfo> = try {
        downloadFile(httpBase, token, target, maxBytes)
    } catch (cause: TimeoutCancellationException) { failure("fetchToFile", cause, FailureKind.TIMED_OUT) }
      catch (cause: CancellationException) { throw cause }
      catch (cause: Exception) { failure("fetchToFile", cause,
          if (cause is IllegalArgumentException) FailureKind.INVALID_DATA else FailureKind.IO) }

    private suspend fun downloadFile(httpBase: String, token: String, target: FileWriteHandle,
        maxBytes: Long): PortResult<FileTransferInfo> {
        val download = FileDownload(target, minOf(maxBytes, MAX_STREAM_BYTES))
        var complete = false
        return try {
            withContext(Dispatchers.IO) {
                try {
                    require(maxBytes in 0..MAX_STREAM_BYTES && target.expectedSize in 0..maxBytes) {
                        "The file exceeds its download limit."
                    }
                    val timeoutMs = fileTimeoutMs(target.expectedSize)
                    withTimeout(timeoutMs) {
                        transfer("fetchToFile", httpBase, timeoutMs, { base, credential ->
                            val media = token.removePrefix("MEDIA:hermes-relay://")
                            require(media.isNotBlank() && media != "." && media != "..") { "The media token is invalid." }
                            Request.Builder().url(base.newBuilder().addPathSegment("media").addPathSegment(media).build())
                                .header("Authorization", "Bearer $credential").singleAttempt()
                        }) { response -> download.copy(response.body) }
                    }
                } finally {
                    // Successful callback completion is not enough: cancellation may have won the continuation race.
                    if (!currentCoroutineContext().isActive) download.abort()
                }
            }.also { complete = it is PortResult.Success }
        } finally { if (!complete) withContext(NonCancellable + Dispatchers.IO) { download.abort() } }
    }

    internal fun fileTimeoutMs(size: Long): Long = maxOf(fileMinimumTimeoutMs,
        ((size + 1024 * 1024 - 1) / (1024 * 1024)) * fileMillisPerMiB)

    private suspend fun <T> transfer(
        operation: String, httpBase: String, timeoutMs: Long,
        request: (HttpUrl, String) -> Request, read: (Response) -> T,
    ): PortResult<T> = try {
        val credential = withTimeout(5_000) {
            when (val value = sessions.token()) {
                ReadValue.Absent -> throw MediaFailure(PortFailure(FailureKind.UNAVAILABLE,
                    IllegalStateException("The relay session is unavailable.")))
                is ReadValue.Unreadable -> throw MediaFailure(value.failure)
                is ReadValue.Present -> value.value.also { require(it.isNotBlank()) { "The relay session is empty." } }
            }
        }
        val base = (RelayUrl.parse(httpBase).httpBase + "/").toHttpUrl()
        val call = client.newCall(request(base, credential))
        call.timeout().timeout(timeoutMs, TimeUnit.MILLISECONDS)
        execute(operation, call, read)
    } catch (cause: TimeoutCancellationException) {
        failure(operation, cause, FailureKind.TIMED_OUT)
    } catch (cause: CancellationException) {
        throw cause
    } catch (cause: Exception) {
        if (cause is MediaFailure) failure(operation, cause.failure)
        else failure(operation, cause, if (cause is IllegalArgumentException) FailureKind.INVALID_DATA else FailureKind.IO)
    }

    private suspend fun <T> execute(operation: String, call: Call, read: (Response) -> T): PortResult<T> =
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    val status = call.request().tag(RelayAttempt::class.java)?.refusedHttpStatus
                    if (continuation.isActive) continuation.resume(failure(operation, e,
                        when {
                            status != null -> FailureKind.REJECTED
                            e is InterruptedIOException -> FailureKind.TIMED_OUT
                            else -> FailureKind.IO
                        }, status))
                }

                override fun onResponse(call: Call, response: Response) {
                    val result = try {
                        response.use {
                            if (!continuation.isActive) return
                            if (!response.isSuccessful) failure(operation, IOException("The relay returned HTTP ${response.code}."),
                                FailureKind.REJECTED, response.code)
                            else PortResult.Success(read(response))
                        }
                    } catch (cause: Exception) {
                        failure(operation, cause, when (cause) {
                            is InterruptedIOException -> FailureKind.TIMED_OUT
                            is IOException -> FailureKind.IO
                            else -> FailureKind.INVALID_DATA
                        }, response.code)
                    }
                    continuation.resume(result)
                }
            })
        }

    private fun failure(operation: String, cause: Throwable, kind: FailureKind, status: Int? = null): PortResult.Failure =
        failure(operation, PortFailure(kind, cause, httpStatus = status))

    private fun failure(operation: String, value: PortFailure): PortResult.Failure {
        val safe = value.copy(cause = FailureDiagnostics.sanitized(value.cause))
        logger.error("RelayMediaClient", operation, "state=failed http_status=${value.httpStatus}", safe.cause)
        return PortResult.Failure(safe)
    }

    private class MediaFailure(val failure: PortFailure) : Exception("The session could not be read.", failure.cause)

    companion object {
        const val MAX_FILE_BYTES: Long = 25L * 1024 * 1024
        const val MAX_STREAM_BYTES: Long = 256L * 1024 * 1024
    }
}
