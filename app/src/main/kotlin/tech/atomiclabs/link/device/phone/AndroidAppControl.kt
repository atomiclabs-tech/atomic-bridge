// Adapted from upstream app/src/main/kotlin/com/hermesandroid/relay/network/relay/BridgeCommandHandler.kt and app/src/main/kotlin/com/hermesandroid/relay/accessibility/ActionExecutor.kt at da782a8.
package tech.atomiclabs.link.device.phone

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.*
import tech.atomiclabs.link.core.command.FailureDiagnostics
import tech.atomiclabs.link.core.log.Logger
import tech.atomiclabs.link.core.port.*

class AndroidAppControl(
    private val context: Context,
    private val logger: Logger,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
) : AppControl {
    @Suppress("DEPRECATION")
    override suspend fun launchableApps(): PortResult<List<LaunchableApp>> = bounded("list") {
        val packages = context.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        packages.queryIntentActivities(launcher, 0).map {
            checkNotNull(it.activityInfo) { "Launcher activity metadata is missing." }
        }.distinctBy { it.packageName }.map {
            LaunchableApp(checkNotNull(it.packageName), packages.getApplicationLabel(checkNotNull(it.applicationInfo)).toString())
        }
    }

    override suspend fun launch(packageName: String): AppLaunchOutcome = when (val result = bounded("launch") {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
        if (intent == null) {
            report("launch", IllegalStateException("No launcher activity.")); AppLaunchOutcome.NotFound
        } else try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            AppLaunchOutcome.Launched
        } catch (cause: ActivityNotFoundException) {
            report("launch", cause); AppLaunchOutcome.NotFound
        }
    }) {
        is PortResult.Success -> result.value
        is PortResult.Failure -> AppLaunchOutcome.Failed(result.failure)
    }

    override suspend fun startActivity(intent: IntentSpec): ActivityOutcome = when (val result = bounded("startActivity") {
        val built = build(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (built.resolveActivity(context.packageManager) == null) {
            report("startActivity", IllegalStateException("No intent handler.")); ActivityOutcome.NoHandler
        } else try {
            context.startActivity(built)
            ActivityOutcome.Started
        } catch (cause: ActivityNotFoundException) {
            report("startActivity", cause); ActivityOutcome.NoHandler
        }
    }) {
        is PortResult.Success -> result.value
        is PortResult.Failure -> ActivityOutcome.Failed(result.failure)
    }

    override suspend fun sendBroadcast(intent: IntentSpec): PortResult<Unit> = bounded("broadcast") {
        context.sendBroadcast(build(intent))
    }

    private fun build(spec: IntentSpec): Intent = Intent(spec.action).apply {
        spec.data?.let { data = Uri.parse(it) }
        spec.packageName?.let { setPackage(it) }
        spec.component?.let { component = ComponentName(it.packageName, it.className) }
        spec.category?.let { addCategory(it) }
        spec.extras.forEach { (name, extra) -> when (extra) {
            is IntentExtra.Text -> putExtra(name, extra.value)
            is IntentExtra.Integer -> putExtra(name, extra.value)
            is IntentExtra.Decimal -> putExtra(name, extra.value)
            is IntentExtra.Flag -> putExtra(name, extra.value)
        } }
    }

    private suspend fun <T> bounded(operation: String, block: () -> T): PortResult<T> = try {
        withTimeout(5_000) { withContext(dispatcher) { PortResult.Success(block()) } }
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
        logger.error("AndroidAppControl", operation, "state=failed", FailureDiagnostics.sanitized(cause))

    private fun failed(operation: String, cause: Exception, kind: FailureKind): PortResult.Failure {
        report(operation, cause)
        return PortResult.Failure(PortFailure(kind, cause))
    }
}
