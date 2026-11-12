package tech.atomiclabs.link.service

import android.content.Context
import android.os.Build
import android.provider.Settings
import kotlinx.coroutines.*
import tech.atomiclabs.link.core.command.FailureDiagnostics
import tech.atomiclabs.link.core.log.Logger
import tech.atomiclabs.link.core.port.*

class AndroidDeviceIdentity(
    private val context: Context,
    private val logger: Logger,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val model: () -> String = { Build.MODEL },
    private val release: () -> String = { Build.VERSION.RELEASE },
) : DeviceIdentity {
    @Suppress("DEPRECATION")
    override suspend fun read(): PortResult<DeviceDescription> = try {
        withTimeout(5_000) { withContext(dispatcher) {
            val actualModel = model().also { check(it.isNotBlank()) { "The device model is unavailable." } }
            val configured = Settings.Global.getString(context.contentResolver, "device_name")
            val name = if (configured.isNullOrBlank()) {
                logger.warn("AndroidDeviceIdentity", "deviceNameFallback", "state=missing_device_name")
                actualModel
            } else configured
            val platform = release().also { check(it.isNotBlank()) { "The platform version is unavailable." } }
            val packageName = context.packageName
            val version = checkNotNull(context.packageManager.getPackageInfo(packageName, 0).versionName) {
                "The application version is unavailable."
            }
            check(version.isNotBlank()) { "The application version is empty." }
            PortResult.Success(DeviceDescription(name, name, actualModel, "Android $platform", packageName, version))
        } }
    } catch (cause: TimeoutCancellationException) {
        failure(cause, FailureKind.TIMED_OUT)
    } catch (cause: CancellationException) {
        throw cause
    } catch (cause: Exception) {
        failure(cause, if (cause is SecurityException) FailureKind.PERMISSION_DENIED else FailureKind.PLATFORM)
    }

    private fun failure(cause: Exception, kind: FailureKind): PortResult.Failure {
        val safe = FailureDiagnostics.sanitized(cause)
        logger.error("AndroidDeviceIdentity", "read", "state=failed", safe)
        return PortResult.Failure(PortFailure(kind, safe))
    }
}
