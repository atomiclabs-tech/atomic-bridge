package tech.atomiclabs.link.device.access

import android.hardware.display.DisplayManager
import android.util.DisplayMetrics as AndroidDisplayMetrics
import android.view.Display
import kotlinx.coroutines.*
import tech.atomiclabs.link.core.command.FailureDiagnostics
import tech.atomiclabs.link.core.log.Logger
import tech.atomiclabs.link.core.port.*

class AndroidScreenMetrics(
    private val displays: DisplayManager, private val logger: Logger,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
) : ScreenMetrics {
    @Suppress("DEPRECATION")
    override suspend fun read(): PortResult<DisplayMetrics> = try {
        PortResult.Success(withTimeout(5_000) { withContext(dispatcher) {
            val display = checkNotNull(displays.getDisplay(Display.DEFAULT_DISPLAY)) { "The default display is unavailable." }
            val metrics = AndroidDisplayMetrics()
            display.getRealMetrics(metrics)
            check(metrics.widthPixels > 0 && metrics.heightPixels > 0 && metrics.density.isFinite() && metrics.density > 0) {
                "The display metrics are invalid."
            }
            DisplayMetrics(metrics.widthPixels, metrics.heightPixels, metrics.density)
        } })
    } catch (cause: TimeoutCancellationException) {
        currentCoroutineContext().ensureActive()
        failure(cause, FailureKind.TIMED_OUT)
    } catch (cause: CancellationException) {
        throw cause
    } catch (cause: Exception) {
        failure(cause, if (cause is SecurityException) FailureKind.PERMISSION_DENIED else FailureKind.PLATFORM)
    }

    private fun failure(cause: Exception, kind: FailureKind): PortResult.Failure {
        logger.error("AndroidScreenMetrics", "read", "state=metrics_failed", FailureDiagnostics.sanitized(cause))
        return PortResult.Failure(PortFailure(kind, cause))
    }
}
