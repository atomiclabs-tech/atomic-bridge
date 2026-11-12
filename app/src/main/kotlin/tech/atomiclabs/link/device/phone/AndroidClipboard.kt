// Adapted from upstream app/src/main/kotlin/com/hermesandroid/relay/accessibility/ActionExecutor.kt at da782a8.
package tech.atomiclabs.link.device.phone

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import kotlinx.coroutines.*
import tech.atomiclabs.link.R
import tech.atomiclabs.link.core.command.FailureDiagnostics
import tech.atomiclabs.link.core.log.Logger
import tech.atomiclabs.link.core.port.*

class AndroidClipboard(
    private val context: Context,
    private val trampoline: Trampoline,
    private val logger: Logger,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
) : ClipboardAccess {
    override suspend fun read(): PortResult<String> = bounded("read") {
        when (val result = trampoline.readClipboard()) {
            is PortResult.Success -> result
            is PortResult.Failure -> {
                report("read", result.failure.cause)
                result
            }
        }
    }

    override suspend fun write(text: String): PortResult<Unit> = bounded("write") {
        withContext(dispatcher) {
            val clipboard = checkNotNull(context.getSystemService(ClipboardManager::class.java)) {
                "Clipboard service is unavailable."
            }
            clipboard.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.app_name), text))
            PortResult.Success(Unit)
        }
    }

    private suspend fun <T> bounded(operation: String, block: suspend () -> PortResult<T>): PortResult<T> = try {
        withTimeout(4_000) { block() }
    } catch (cause: TimeoutCancellationException) {
        failed(operation, cause, FailureKind.TIMED_OUT)
    } catch (cause: CancellationException) {
        throw cause
    } catch (cause: SecurityException) {
        failed(operation, cause, FailureKind.PERMISSION_DENIED)
    } catch (cause: Exception) {
        failed(operation, cause, FailureKind.PLATFORM)
    }

    private fun report(operation: String, cause: Throwable) =
        logger.error("AndroidClipboard", operation, "state=failed", FailureDiagnostics.sanitized(cause))

    private fun failed(operation: String, cause: Exception, kind: FailureKind): PortResult.Failure {
        report(operation, cause)
        return PortResult.Failure(PortFailure(kind, cause))
    }
}
