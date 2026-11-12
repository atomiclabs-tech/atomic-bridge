package tech.atomiclabs.link.ui

import android.app.AppOpsManager
import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tech.atomiclabs.link.core.command.FailureDiagnostics
import tech.atomiclabs.link.core.log.Logger
import tech.atomiclabs.link.core.port.*

/** Reads only this application's restriction; it never changes an operation or permission. */
class AndroidRestrictedSettings(
    private val context: Context,
    private val logger: Logger,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : RestrictedSettingsState {
    private val probeLock = Mutex()
    private var probeUnavailable = false

    override suspend fun read(): PortResult<RestrictedSettingsStatus> = try {
        withTimeout(5_000) {
            probeLock.withLock {
                if (probeUnavailable) PortResult.Success(RestrictedSettingsStatus.UNSUPPORTED)
                else withContext(dispatcher) { observe() }
            }
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

    @Suppress("DEPRECATION")
    private fun observe(): PortResult<RestrictedSettingsStatus> {
        val operations = context.getSystemService(AppOpsManager::class.java) ?: return failed(
            IllegalStateException("App operations service is unavailable."), FailureKind.UNAVAILABLE,
        )
        val uid = context.applicationInfo.uid
        val packageName = context.packageName
        val mode = try {
            // The public raw self-check accepts names; this operation has no public SDK constant.
            operations.unsafeCheckOpRawNoThrow(OPERATION, uid, packageName)
        } catch (cause: SecurityException) {
            // This probe is inaccessible, independently of whether the setting is allowed.
            probeUnavailable = true
            return unsupported("capability=unavailable", cause)
        } catch (cause: IllegalArgumentException) {
            return unsupported("operation=unrecognized", cause)
        }
        val state = when (mode) {
            AppOpsManager.MODE_ALLOWED -> RestrictedSettingsStatus.ALLOWED
            AppOpsManager.MODE_IGNORED, AppOpsManager.MODE_ERRORED -> RestrictedSettingsStatus.RESTRICTED
            // Defaults and foreground-only modes do not establish this persistent prerequisite.
            else -> return unsupported("mode=$mode")
        }
        return PortResult.Success(state)
    }

    private fun unsupported(context: String, cause: Exception? = null): PortResult<RestrictedSettingsStatus> {
        logger.warn("AndroidRestrictedSettings", "read", "state=unknown $context",
            cause?.let(FailureDiagnostics::sanitized))
        return PortResult.Success(RestrictedSettingsStatus.UNSUPPORTED)
    }

    private fun failed(cause: Exception, kind: FailureKind): PortResult.Failure {
        logger.error("AndroidRestrictedSettings", "read", "state=failed kind=$kind", FailureDiagnostics.sanitized(cause))
        return PortResult.Failure(PortFailure(kind, cause))
    }

    private companion object { const val OPERATION = "android:access_restricted_settings" }
}
