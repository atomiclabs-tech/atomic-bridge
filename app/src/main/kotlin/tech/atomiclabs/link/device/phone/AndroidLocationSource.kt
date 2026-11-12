// Adapted from upstream app/src/main/kotlin/com/hermesandroid/relay/accessibility/ActionExecutor.kt at da782a8.
package tech.atomiclabs.link.device.phone
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlinx.coroutines.*
import tech.atomiclabs.link.core.command.FailureDiagnostics
import tech.atomiclabs.link.core.log.Logger
import tech.atomiclabs.link.core.port.*

class AndroidLocationSource(private val context: Context, private val logger: Logger,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val executor: Executor = Executor { it.run() }) : LocationSource {
    override suspend fun lastKnown(): PortResult<List<LocationFix>> = try {
        withTimeout(5_000) { withContext(dispatcher) {
            permissions()
            val manager = checkNotNull(context.getSystemService(LocationManager::class.java))
            PortResult.Success(manager.getProviders(true).mapNotNull { manager.getLastKnownLocation(it)?.let(::fix) })
        } }
    } catch (cause: CancellationException) {
        if (cause is TimeoutCancellationException) failure(cause, FailureKind.TIMED_OUT) else throw cause
    } catch (cause: Denied) { failure(cause, FailureKind.PERMISSION_DENIED, cause.permission)
    } catch (cause: SecurityException) { failure(cause, FailureKind.PERMISSION_DENIED, Manifest.permission.ACCESS_FINE_LOCATION)
    } catch (cause: Exception) { failure(cause, FailureKind.PLATFORM) }

    override suspend fun current(timeoutMs: Long): ReadValue<LocationFix> = try {
        withTimeout(timeoutMs.coerceIn(1, 10_000)) { withContext(dispatcher) {
            permissions()
            val manager = checkNotNull(context.getSystemService(LocationManager::class.java))
            val providers = manager.getProviders(true)
            val provider = if (LocationManager.GPS_PROVIDER in providers) LocationManager.GPS_PROVIDER else providers.firstOrNull()
            if (provider == null) ReadValue.Absent else {
                val signal = CancellationSignal()
                val location = try {
                    suspendCancellableCoroutine<Location?> { continuation ->
                        manager.getCurrentLocation(provider, signal, executor) { location ->
                            if (continuation.isActive) continuation.resume(location)
                        }
                    }
                } finally { signal.cancel() }
                if (location == null) ReadValue.Absent else ReadValue.Present(fix(location))
            }
        } }
    } catch (cause: CancellationException) {
        if (cause is TimeoutCancellationException) ReadValue.Unreadable(failure(cause, FailureKind.TIMED_OUT).failure) else throw cause
    } catch (cause: Denied) { ReadValue.Unreadable(failure(cause, FailureKind.PERMISSION_DENIED, cause.permission).failure)
    } catch (cause: SecurityException) {
        ReadValue.Unreadable(failure(cause, FailureKind.PERMISSION_DENIED, Manifest.permission.ACCESS_FINE_LOCATION).failure)
    } catch (cause: Exception) { ReadValue.Unreadable(failure(cause, FailureKind.PLATFORM).failure) }

    private fun permissions() {
        for (permission in listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_BACKGROUND_LOCATION)) {
            if (context.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) throw Denied(permission)
        }
    }
    private fun fix(location: Location): LocationFix {
        check(location.latitude.isFinite() && location.latitude in -90.0..90.0 &&
            location.longitude.isFinite() && location.longitude in -180.0..180.0 &&
            location.accuracy.isFinite() && location.accuracy >= 0 && location.time >= 0) { "Location fix is invalid." }
        val altitude = if (location.hasAltitude()) location.altitude else null
        check(altitude == null || altitude.isFinite()) { "Location altitude is invalid." }
        return LocationFix(location.latitude, location.longitude, location.accuracy,
            checkNotNull(location.provider), location.time, altitude)
    }
    private fun failure(cause: Exception, kind: FailureKind, permission: String? = null): PortResult.Failure {
        logger.error("AndroidLocationSource", "read", "state=failed", FailureDiagnostics.sanitized(cause))
        return PortResult.Failure(PortFailure(kind, cause, permission))
    }
    private class Denied(val permission: String) : SecurityException("Location permission is missing.")
}
