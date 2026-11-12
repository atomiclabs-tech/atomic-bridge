package tech.atomiclabs.link.storage

import android.content.SharedPreferences
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tech.atomiclabs.link.core.command.FailureDiagnostics
import tech.atomiclabs.link.core.log.Logger
import tech.atomiclabs.link.core.port.*

/** Runtime injects its private SharedPreferences; no relay endpoint or account default is supplied. */
class AppSettings(
    private val preferences: SharedPreferences,
    private val logger: Logger,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val newDeviceId: () -> String = { UUID.randomUUID().toString() },
) : SettingsStore {
    private val ids = Mutex()
    override suspend fun relayUrl(): ReadValue<String> = read("relay_url")
    override suspend fun saveRelayUrl(url: String): PortResult<Unit> = write("relay_url", url)

    override suspend fun deviceId(): ReadValue<String> = ids.withLock {
        when (val current = read("device_id")) {
            is ReadValue.Present -> try {
                validId(current.value); current
            } catch (cause: Exception) { ReadValue.Unreadable(failure("device_id", cause)) }
            is ReadValue.Unreadable -> current
            ReadValue.Absent -> {
                try {
                    val generated = newDeviceId()
                    validId(generated)
                    when (val saved = write("device_id", generated)) {
                        is PortResult.Success -> ReadValue.Present(generated)
                        is PortResult.Failure -> ReadValue.Unreadable(saved.failure)
                    }
                } catch (cause: Exception) {
                    if (cause is CancellationException) throw cause
                    ReadValue.Unreadable(failure("device_id", cause))
                }
            }
        }
    }

    override suspend fun saveDeviceId(id: String): PortResult<Unit> = ids.withLock {
        try { validId(id); write("device_id", id) }
        catch (cause: Exception) {
            if (cause is CancellationException) throw cause
            PortResult.Failure(failure("device_id", cause))
        }
    }

    private suspend fun read(key: String): ReadValue<String> = try {
        val value = withTimeout(2_000) { withContext(dispatcher) { preferences.getString(key, null) } }
        value?.let { ReadValue.Present(it) } ?: ReadValue.Absent
    } catch (cause: Exception) {
        if (cause is CancellationException && cause !is TimeoutCancellationException) throw cause
        ReadValue.Unreadable(failure(key, cause))
    }

    private suspend fun write(key: String, value: String): PortResult<Unit> = try {
        require(value.isNotBlank()) { "The setting value is empty." }
        withTimeout(2_000) { withContext(dispatcher) {
            check(preferences.edit().putString(key, value).commit()) { "The setting write failed." }
        } }
        PortResult.Success(Unit)
    } catch (cause: Exception) {
        if (cause is CancellationException && cause !is TimeoutCancellationException) throw cause
        PortResult.Failure(failure(key, cause))
    }

    private fun validId(value: String) {
        require(UUID.fromString(value).toString().equals(value, ignoreCase = true)) { "The saved device id is invalid." }
    }

    private fun failure(key: String, cause: Exception): PortFailure {
        val safe = FailureDiagnostics.sanitized(cause)
        logger.error("AppSettings", key, "state=setting_failed", safe)
        return PortFailure(if (cause is TimeoutCancellationException) FailureKind.TIMED_OUT else FailureKind.IO, safe)
    }
}
