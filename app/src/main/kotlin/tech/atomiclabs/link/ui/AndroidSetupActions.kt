package tech.atomiclabs.link.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import tech.atomiclabs.link.core.command.FailureDiagnostics
import tech.atomiclabs.link.core.log.Logger
import tech.atomiclabs.link.core.port.*

class AndroidSetupActions(private val activity: Activity, private val logger: Logger) : SetupActions {
    private data class Pending(val permission: Permission, val names: Array<String>, val result: CompletableDeferred<IntArray>)
    private var pending: Pending? = null

    override suspend fun openStatus(): PortResult<Unit> = bounded("openStatus") {
        open(Intent(activity, StatusActivity::class.java))
    }

    override suspend fun openAppInfo(): PortResult<Unit> = bounded("openAppInfo") {
        open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri()))
    }

    override suspend fun openPermissionSettings(permission: Permission): PortResult<Unit> = bounded("openSettings", permission) {
        val intent = when (permission) {
            Permission.ACCESSIBILITY -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            Permission.BATTERY_EXEMPT -> Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, packageUri())
            Permission.NOTIFICATIONS -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, activity.packageName)
            Permission.NOTIFICATION_ACCESS -> Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            Permission.ALL_FILES -> Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, packageUri())
            else -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri())
        }
        open(intent)
    }

    override suspend fun requestPermission(permission: Permission): PortResult<Unit> = bounded("requestPermission", permission) {
        val names = runtimePermissions(permission)
        if (names == null) return@bounded openPermissionSettings(permission)
        if (pending != null) return@bounded failure(
            "requestPermission", IllegalStateException("A permission request is already pending."), FailureKind.REJECTED, permission,
        )
        val request = Pending(permission, names, CompletableDeferred())
        pending = request
        try {
            activity.requestPermissions(names, REQUEST_CODE)
            val grants = request.result.await()
            val granted = if (permission == Permission.LOCATION) {
                grants.any { it == PackageManager.PERMISSION_GRANTED }
            } else grants.all { it == PackageManager.PERMISSION_GRANTED }
            if (granted) PortResult.Success(Unit) else failure(
                "requestPermission", SecurityException("The requested permission was denied."),
                FailureKind.PERMISSION_DENIED, permission,
            )
        } finally {
            if (pending === request) pending = null
        }
    }

    fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode != REQUEST_CODE) return
        val request = pending ?: return
        if (!permissions.contentEquals(request.names) || grantResults.size != request.names.size) {
            request.result.completeExceptionally(IllegalStateException("Permission callback did not match the request."))
        } else {
            request.result.complete(grantResults.copyOf())
        }
    }

    private fun runtimePermissions(permission: Permission): Array<String>? = when (permission) {
        Permission.NOTIFICATIONS -> arrayOf(Manifest.permission.POST_NOTIFICATIONS)
        Permission.SMS -> arrayOf(Manifest.permission.SEND_SMS)
        Permission.CALL -> arrayOf(Manifest.permission.CALL_PHONE)
        Permission.CONTACTS -> arrayOf(Manifest.permission.READ_CONTACTS)
        Permission.LOCATION -> arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        Permission.MICROPHONE -> arrayOf(Manifest.permission.RECORD_AUDIO)
        Permission.PHONE_STATE -> arrayOf(Manifest.permission.READ_PHONE_STATE)
        else -> null
    }

    private fun packageUri(): Uri = Uri.fromParts("package", activity.packageName, null)

    private fun open(intent: Intent): PortResult<Unit> {
        checkNotNull(intent.resolveActivity(activity.packageManager)) { "No system screen can handle the request." }
        activity.startActivity(intent)
        return PortResult.Success(Unit)
    }

    private suspend fun bounded(
        operation: String,
        permission: Permission? = null,
        block: suspend () -> PortResult<Unit>,
    ): PortResult<Unit> = try {
        withTimeout(25_000) { block() }
    } catch (cause: TimeoutCancellationException) {
        failure(operation, cause, FailureKind.TIMED_OUT, permission)
    } catch (cause: CancellationException) {
        throw cause
    } catch (cause: SecurityException) {
        failure(operation, cause, FailureKind.PERMISSION_DENIED, permission)
    } catch (cause: Exception) {
        failure(operation, cause, FailureKind.PLATFORM, permission)
    }

    private fun failure(
        operation: String, cause: Exception, kind: FailureKind, permission: Permission?,
    ): PortResult.Failure {
        logger.error("AndroidSetupActions", operation, "permission=$permission state=failed", FailureDiagnostics.sanitized(cause))
        return PortResult.Failure(PortFailure(kind, cause, permission = permission?.name))
    }

    private companion object { const val REQUEST_CODE = 2407 }
}
