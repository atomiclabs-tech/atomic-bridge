// Ported from upstream app/src/main/kotlin/com/hermesandroid/relay/network/relay/BridgeCommandHandler.kt at commit da782a8.
package tech.atomiclabs.link.core.phone

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import tech.atomiclabs.link.core.command.*
import tech.atomiclabs.link.core.log.Logger
import tech.atomiclabs.link.core.port.*

class ShareRoutes(
    private val media: MediaTransfer,
    private val files: OutboundFiles,
    private val launcher: ShareLauncher,
    private val clock: Clock,
    private val logger: Logger,
    private val httpBase: suspend () -> String,
) : RouteGroup {
    override val routes = listOf("/share_media", "/send_mms").map { path ->
        Route("POST", path, { RouteNeeds(display = true) }) { execute(it, path == "/send_mms") }
    }

    private suspend fun execute(body: BodyParams, mms: Boolean): CommandResult = try {
        when (val result = files.prune(clock.epochMs() - 24L * 60 * 60 * 1_000)) {
            is PortResult.Success -> Unit
            is PortResult.Failure -> failed("prune", result.failure)
        }
        val specs = AttachmentSpec.parse(body)
        val recipient = if (mms) nonblank(body, "to", required = true) else null
        val text = if (mms) body.requiredString("body") else body.string("text")
        val title = body.string("title")
        val target = nonblank(body, "package")
        val endpoint = httpBase()
        if (endpoint.isBlank()) failed("endpoint", PortFailure(FailureKind.INVALID_DATA,
            IllegalStateException("The media address is missing.")), ErrorCode.MEDIA_FETCH_FAILED)
        val attachments = specs.map { spec -> prepare(endpoint, spec) }
        val request = ShareRequest(attachments, text, title, target, recipient)
        when (val result = launcher.open(request)) {
            ShareLaunchOutcome.NoHandler -> CommandResult.Failure(ErrorCode.NO_HANDLER,
                "No application can open this compose request.")
            is ShareLaunchOutcome.Failed -> failed("open", result.failure)
            is ShareLaunchOutcome.Opened -> CommandResult.Success(buildJsonObject {
                put("status", "compose_opened")
                put("note", "Send has not been tapped. Tap Send to deliver.")
                put("attachments", attachments.size)
                put("content_types", JsonArray(attachments.map { JsonPrimitive(it.contentType) }))
                put("package", result.packageName?.let { JsonPrimitive(it) } ?: JsonNull)
                if (mms) put("to", recipient)
            })
        }
    } catch (cause: ShareFailure) {
        cause.result
    }

    private suspend fun prepare(endpoint: String, spec: AttachmentSpec): OutboundAttachment {
        val timeoutMs = 20_000L
        val result = try {
            withTimeout(timeoutMs) { media.fetch(endpoint, spec.token) }
        } catch (cause: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            failed("fetch", PortFailure(FailureKind.TIMED_OUT, cause), ErrorCode.MEDIA_FETCH_FAILED)
        }
        val fetched = when (result) {
            is PortResult.Success -> result.value
            is PortResult.Failure -> failed("fetch", result.failure, ErrorCode.MEDIA_FETCH_FAILED)
        }
        val contentType = spec.contentType ?: fetched.contentType?.trim()?.takeIf { it.isNotEmpty() }
            ?: failed("contentType", PortFailure(FailureKind.INVALID_DATA,
                IllegalStateException("The fetched media has no content type.")), ErrorCode.MEDIA_FETCH_FAILED)
        return when (val result = files.write(fetched.bytes, spec.fileName, contentType)) {
            is PortResult.Success -> result.value
            is PortResult.Failure -> failed("write", result.failure)
        }
    }

    private fun nonblank(body: BodyParams, name: String, required: Boolean = false): String? {
        val value = if (required) body.requiredString(name) else body.string(name)
        return value?.trim()?.also { if (it.isEmpty()) throw BadRequestException(name) }
    }

    private fun failed(operation: String, failure: PortFailure, code: ErrorCode? = null): Nothing {
        logger.error("ShareRoutes", operation, "state=failed", FailureDiagnostics.sanitized(failure.cause))
        val resultCode = code ?: when (failure.kind) {
            FailureKind.PERMISSION_DENIED -> ErrorCode.PERMISSION_DENIED
            FailureKind.TIMED_OUT -> ErrorCode.TIMEOUT
            else -> ErrorCode.INTERNAL_ERROR
        }
        val detail = failure.httpStatus?.let { "HTTP status $it" } ?: failure.kind.name.lowercase()
        throw ShareFailure(CommandResult.Failure(resultCode, "The share $operation failed with $detail."), failure.cause)
    }

    private class ShareFailure(val result: CommandResult.Failure, cause: Throwable) :
        IllegalStateException("The share operation failed.", cause)
}
