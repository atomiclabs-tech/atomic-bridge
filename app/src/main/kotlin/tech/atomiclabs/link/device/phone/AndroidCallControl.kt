// Adapted from upstream app/src/main/kotlin/com/hermesandroid/relay/accessibility/ActionExecutor.kt at da782a8.
package tech.atomiclabs.link.device.phone
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import kotlinx.coroutines.*
import tech.atomiclabs.link.core.command.FailureDiagnostics
import tech.atomiclabs.link.core.log.Logger
import tech.atomiclabs.link.core.port.*

class AndroidCallControl(private val context: Context, private val logger: Logger,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate) : CallControl {
    override suspend fun call(number: String): PortResult<Unit> = try {
        withTimeout(5_000) { withContext(dispatcher) {
            if (context.checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
                throw SecurityException("Call permission is missing.")
            }
            val intent = Intent(Intent.ACTION_CALL, Uri.fromParts("tel", number, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            checkNotNull(intent.resolveActivity(context.packageManager)) { "No call activity is available." }
            context.startActivity(intent)
            PortResult.Success(Unit)
        } }
    } catch (cause: TimeoutCancellationException) { failed(cause, FailureKind.TIMED_OUT)
    } catch (cause: CancellationException) { throw cause
    } catch (cause: SecurityException) { failed(cause, FailureKind.PERMISSION_DENIED)
    } catch (cause: Exception) { failed(cause, FailureKind.PLATFORM) }
    private fun failed(cause: Exception, kind: FailureKind): PortResult.Failure {
        logger.error("AndroidCallControl", "call", "state=failed", FailureDiagnostics.sanitized(cause))
        return PortResult.Failure(PortFailure(kind, cause,
            permission = if (kind == FailureKind.PERMISSION_DENIED) Manifest.permission.CALL_PHONE else null))
    }
}
