package tech.atomiclabs.link.core.port

import kotlinx.coroutines.flow.Flow

/** Opens status/settings or requests a permission; refused starts and permission requests are failures. */
interface SetupActions {
    suspend fun openStatus(): PortResult<Unit>
    /** Opens this app's system App info independently of the nine permission rows. */
    suspend fun openAppInfo(): PortResult<Unit>
    suspend fun openPermissionSettings(permission: Permission): PortResult<Unit>
    suspend fun requestPermission(permission: Permission): PortResult<Unit>
}

/** Pairing accepts a Mac-supplied code and retains the old session until authentication succeeds. */
interface ConnectionControl {
    suspend fun connect(): PortResult<Unit>
    suspend fun pair(code: String): PortResult<Unit>
    suspend fun applyRelayUrl(): PortResult<Unit>
}

/** Presenter observations carry explicit failures without exposing pairing credentials. */
interface StatusSource {
    val changes: Flow<PortResult<StatusSnapshot>>
    suspend fun current(): PortResult<StatusSnapshot>
}

enum class ConnectionPhase {
    UNPAIRED, CONNECTING, AUTHENTICATING, ONLINE, BACKOFF, REAUTH_REQUIRED, PAIRING_REJECTED, SESSION_UNREADABLE,
}

sealed interface GrantExpiry {
    data object Missing : GrantExpiry
    data object Never : GrantExpiry
    data class At(val epochSeconds: Long) : GrantExpiry
}

enum class ConnectionFailureOrigin { NONE, TRANSPORT, CONFIGURATION, SESSION, AUTHENTICATION, INTERNAL }

data class StatusSnapshot(
    val connection: ConnectionPhase,
    val relayUrl: String?,
    val deviceId: String?,
    val bridgeGrant: GrantExpiry,
    val appVersion: String,
    val failure: PortFailure? = null,
    val failureOrigin: ConnectionFailureOrigin = ConnectionFailureOrigin.NONE,
    val retryScheduled: Boolean = false,
) {
    override fun toString(): String = "StatusSnapshot(connection=$connection, failureOrigin=$failureOrigin, retryScheduled=$retryScheduled)"
}
