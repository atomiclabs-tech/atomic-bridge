// forge-allow: no-test declarations only
package tech.atomiclabs.link.core.port

import kotlinx.coroutines.flow.Flow

/** Ordered socket events; send/close are bounded and checked, never automatically retried. */
interface RelaySocket {
    val events: Flow<SocketEvent>
    suspend fun send(frame: String): PortResult<Unit>
    suspend fun close(code: Int, reason: String): PortResult<Unit>
}

/** Opens one socket with a 10 s connect timeout; failures include upgrade HTTP status when available. */
fun interface RelaySocketFactory {
    suspend fun connect(websocketUrl: String): PortResult<RelaySocket>
}

/** Reports default-network availability; observation/read failures are explicit, never false availability. */
interface NetworkMonitor {
    val changes: Flow<PortResult<Boolean>>
    suspend fun isAvailable(): PortResult<Boolean>
}

/** Distinguishes absent/unreadable sessions; save atomically replaces only after successful pairing. */
interface SessionStore {
    /**
     * Returns one completed load; SessionFilePolicy owns the initial read/decrypt plus three retries, 1 s apart.
     * Consumers do not retry a final Unreadable outcome. Failures leave the file in place.
     * The complete load, including bounded one-shot operations and retry delays, must fit the caller's 60 s budget.
     */
    suspend fun read(): ReadValue<StoredSession>
    suspend fun save(session: StoredSession): PortResult<Unit>
}

/** Storage owns encrypted bytes and key access; cryptographic failure preserves its cause and input file. */
interface SessionCipher {
    suspend fun encrypt(plaintext: ByteArray): PortResult<ByteArray>
    suspend fun decrypt(ciphertext: ByteArray): PortResult<ByteArray>
}

/** Missing settings remain absent; persistence/read failures cannot silently become defaults. */
interface SettingsStore {
    suspend fun relayUrl(): ReadValue<String>
    suspend fun deviceId(): ReadValue<String>
    suspend fun saveRelayUrl(url: String): PortResult<Unit>
    suspend fun saveDeviceId(id: String): PortResult<Unit>
}

/** Device metadata only; the stable generated UUID is owned by SettingsStore and core connection logic. */
fun interface DeviceIdentity {
    suspend fun read(): PortResult<DeviceDescription>
}

/** Supplies the currently adopted session token without exposing storage to network adapters or logging it. */
fun interface SessionTokenProvider {
    suspend fun token(): ReadValue<String>
}

sealed interface SocketEvent {
    data object Opened : SocketEvent
    data class Text(val frame: String) : SocketEvent
    data class Closed(val code: Int, val reason: String) : SocketEvent
    data class Failed(val failure: PortFailure) : SocketEvent
}

data class StoredSession(
    val sessionToken: String,
    val refreshToken: String?,
    val expiresAtEpochSeconds: Long?,
    val grants: Map<String, Long?>,
    val savedAtEpochMs: Long,
)

data class DeviceDescription(
    val name: String,
    val hostname: String,
    val model: String,
    val platform: String,
    val applicationId: String,
    val appVersion: String,
)
