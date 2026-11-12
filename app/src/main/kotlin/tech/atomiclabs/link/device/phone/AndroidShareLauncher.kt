// Ported from upstream app/src/main/kotlin/com/hermesandroid/relay/network/relay/BridgeCommandHandler.kt at commit da782a8.
package tech.atomiclabs.link.device.phone

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Telephony
import kotlinx.coroutines.*
import tech.atomiclabs.link.R
import tech.atomiclabs.link.core.command.FailureDiagnostics
import tech.atomiclabs.link.core.log.Logger
import tech.atomiclabs.link.core.port.*

class AndroidShareLauncher(
    private val context: Context,
    private val logger: Logger,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
) : ShareLauncher {
    override suspend fun open(request: ShareRequest): ShareLaunchOutcome = try {
        withTimeout(3_000) { withContext(dispatcher) { launch(request) } }
    } catch (cause: ActivityNotFoundException) {
        logger.error("AndroidShareLauncher", "open", "state=no_handler", FailureDiagnostics.sanitized(cause))
        ShareLaunchOutcome.NoHandler
    } catch (cause: TimeoutCancellationException) {
        failure(cause, FailureKind.TIMED_OUT)
    } catch (cause: CancellationException) {
        throw cause
    } catch (cause: Exception) {
        failure(cause, when (cause) {
            is SecurityException -> FailureKind.PERMISSION_DENIED
            is IllegalArgumentException -> FailureKind.INVALID_DATA
            else -> FailureKind.PLATFORM
        })
    }

    private fun launch(request: ShareRequest): ShareLaunchOutcome {
        require(request.attachments.isNotEmpty()) { "Compose requires an attachment." }
        val target = request.packageName ?: if (request.recipient != null) Telephony.Sms.getDefaultSmsPackage(context) else null
        if (request.recipient != null && target.isNullOrBlank()) return noHandler()
        require(target == null || target.isNotBlank()) { "The compose package is invalid." }
        val uris = request.attachments.map { attachment ->
            Uri.parse(attachment.uri).also {
                require(it.scheme == "content" && it.authority == context.packageName + ".files") {
                    "The attachment URI is not from the outbound provider."
                }
            }
        }
        val intent = Intent().setAction(if (uris.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE)
        intent.type = contentType(request.attachments)
        if (target != null) intent.setPackage(target)
        intent.addFlags(FLAGS)
        request.text?.let {
            intent.putExtra(Intent.EXTRA_TEXT, it)
            if (request.recipient != null) intent.putExtra("sms_body", it)
        }
        request.recipient?.let { intent.putExtra("address", it) }
        if (uris.size == 1) intent.putExtra(Intent.EXTRA_STREAM, uris.single())
        else intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        val clip = ClipData.newRawUri(context.getString(R.string.app_name), uris.first())
        uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
        intent.clipData = clip
        if (intent.resolveActivity(context.packageManager) == null) return noHandler()
        val activity = if (target != null) intent else Intent.createChooser(intent, request.title).apply {
            addFlags(FLAGS)
            clipData = clip
        }
        context.startActivity(activity)
        return ShareLaunchOutcome.Opened(target)
    }

    private fun contentType(attachments: List<OutboundAttachment>): String {
        val types = attachments.map { it.contentType.substringBefore(';').trim().lowercase() }.distinct()
        require(types.all { it.isNotEmpty() && '/' in it }) { "An attachment content type is invalid." }
        if (types.size == 1) return types.single()
        val majors = types.map { it.substringBefore('/') }.distinct()
        return if (majors.size == 1) majors.single() + "/*" else "*/*"
    }

    private fun noHandler(): ShareLaunchOutcome.NoHandler {
        logger.warn("AndroidShareLauncher", "resolve", "state=no_handler")
        return ShareLaunchOutcome.NoHandler
    }

    private fun failure(cause: Exception, kind: FailureKind): ShareLaunchOutcome.Failed {
        logger.error("AndroidShareLauncher", "open", "state=failed", FailureDiagnostics.sanitized(cause))
        return ShareLaunchOutcome.Failed(PortFailure(kind, cause))
    }

    private companion object {
        const val FLAGS = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
    }
}
