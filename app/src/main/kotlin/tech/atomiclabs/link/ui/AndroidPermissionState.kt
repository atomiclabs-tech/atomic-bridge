package tech.atomiclabs.link.ui

import android.Manifest
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.PowerManager
import android.os.Environment
import android.provider.Settings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import tech.atomiclabs.link.core.command.FailureDiagnostics
import tech.atomiclabs.link.core.log.Logger
import tech.atomiclabs.link.core.port.*

class AndroidPermissionState(
    private val context: Context,
    private val accessibilityComponent: ComponentName,
    private val logger: Logger,
    private val notificationSource: NotificationSource,
) : PermissionState {
    override suspend fun read(): PortResult<PermissionSnapshot> = try {
        withTimeout(25_000) {
            val power = requireNotNull(context.getSystemService(PowerManager::class.java))
            val notifications = requireNotNull(context.getSystemService(NotificationManager::class.java))
            val notificationAccess = when (val result = notificationSource.isEnabled()) {
                is PortResult.Success -> result.value
                is PortResult.Failure -> {
                    logger.error("AndroidPermissionState", "notificationAccess", "state=failed",
                        FailureDiagnostics.sanitized(result.failure.cause))
                    return@withTimeout result
                }
            }
            PortResult.Success(PermissionSnapshot(
                accessibility = accessibilityEnabled(),
                batteryExempt = power.isIgnoringBatteryOptimizations(context.packageName),
                unusedAppRestrictionsDisabled = context.packageManager.isAutoRevokeWhitelisted,
                notifications = granted(Manifest.permission.POST_NOTIFICATIONS) && notifications.areNotificationsEnabled(),
                sms = granted(Manifest.permission.SEND_SMS),
                call = granted(Manifest.permission.CALL_PHONE),
                contacts = granted(Manifest.permission.READ_CONTACTS),
                location = granted(Manifest.permission.ACCESS_FINE_LOCATION) || granted(Manifest.permission.ACCESS_COARSE_LOCATION),
                backgroundLocation = granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION),
                notificationAccess = notificationAccess,
                allFiles = Environment.isExternalStorageManager(),
                microphone = granted(Manifest.permission.RECORD_AUDIO),
                phoneState = granted(Manifest.permission.READ_PHONE_STATE),
            ))
        }
    } catch (cause: TimeoutCancellationException) {
        failed(cause, FailureKind.TIMED_OUT)
    } catch (cause: CancellationException) {
        throw cause
    } catch (cause: SecurityException) {
        failed(cause, FailureKind.PERMISSION_DENIED)
    } catch (cause: Exception) {
        failed(cause, FailureKind.PLATFORM)
    }

    private fun granted(permission: String): Boolean =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun accessibilityEnabled(): Boolean {
        val resolver = context.contentResolver
        val enabled = Settings.Secure.getString(resolver, Settings.Secure.ACCESSIBILITY_ENABLED)
        if (enabled == null || enabled == "0") return false
        check(enabled == "1") { "Accessibility setting has an invalid state." }
        val services = Settings.Secure.getString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        if (services.isNullOrEmpty()) return false
        return services.split(':').map {
            requireNotNull(ComponentName.unflattenFromString(it)) { "Accessibility component setting is invalid." }
        }.any { it == accessibilityComponent }
    }

    private fun failed(cause: Exception, kind: FailureKind): PortResult.Failure {
        logger.error("AndroidPermissionState", "read", "state=failed", FailureDiagnostics.sanitized(cause))
        return PortResult.Failure(PortFailure(kind, cause))
    }
}
