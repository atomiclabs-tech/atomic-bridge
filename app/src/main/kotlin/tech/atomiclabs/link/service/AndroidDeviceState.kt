package tech.atomiclabs.link.service

import android.os.BatteryManager
import kotlinx.coroutines.*
import tech.atomiclabs.link.core.command.FailureDiagnostics
import tech.atomiclabs.link.core.log.Logger
import tech.atomiclabs.link.core.port.*
import tech.atomiclabs.link.core.screen.WindowOrder

class AndroidDeviceState(
    private val battery: BatteryManager,
    private val power: PowerState,
    private val tree: UiTreeSource,
    private val windows: WindowOrder,
    private val activityForPackage: (String) -> String?,
    private val logger: Logger,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : DeviceStateSource {
    override suspend fun read(): PortResult<DeviceSnapshot> = try {
        withTimeout(5_000) { withContext(dispatcher) {
            val percent = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            check(percent in 0..100) { "The battery percentage is unavailable." }
            val interactive = value(power.isInteractive())
            val connected = value(tree.isConnected())
            // Pairing works before accessibility is enabled; no disconnected window read is attempted.
            val foreground = if (connected) windows.foregroundPackage() else null
            PortResult.Success(DeviceSnapshot(percent, interactive, foreground, foreground?.let(activityForPackage)))
        } }
    } catch (cause: TimeoutCancellationException) {
        failure(cause, FailureKind.TIMED_OUT)
    } catch (cause: CancellationException) {
        throw cause
    } catch (cause: Exception) {
        failure(cause, FailureKind.PLATFORM)
    }

    private fun <T> value(result: PortResult<T>): T = when (result) {
        is PortResult.Success -> result.value
        is PortResult.Failure -> throw IllegalStateException("The device state could not be read.", result.failure.cause)
    }

    private fun failure(cause: Exception, kind: FailureKind): PortResult.Failure {
        val safe = FailureDiagnostics.sanitized(cause)
        logger.error("AndroidDeviceState", "read", "state=failed", safe)
        return PortResult.Failure(PortFailure(kind, safe))
    }
}
