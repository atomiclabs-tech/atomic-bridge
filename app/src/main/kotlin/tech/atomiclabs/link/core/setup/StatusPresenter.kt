package tech.atomiclabs.link.core.setup

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tech.atomiclabs.link.core.command.FailureDiagnostics
import tech.atomiclabs.link.core.log.Logger
import tech.atomiclabs.link.core.port.*

/** The composition root supplies the shared URL parser and a platform date formatter. */
class StatusPresenter(
    private val status: StatusSource,
    private val permissions: PermissionState,
    private val restrictedSettings: RestrictedSettingsState,
    private val connection: ConnectionControl,
    private val actions: SetupActions,
    private val settings: SettingsStore,
    private val logger: Logger,
    private val normalizeRelayUrl: (String) -> String,
    private val formatExpiry: (Long) -> String,
) {
    private val mutableState = MutableStateFlow(StatusViewState())
    val state = mutableState.asStateFlow()
    private val pairingConfirmation = Mutex()

    suspend fun observe() {
        refresh()
        try {
            status.changes.collect(::accept)
        } catch (cause: CancellationException) {
            throw cause
        } catch (cause: Exception) {
            failed("observe", "Connection updates could not be read.", cause)
        }
    }

    suspend fun refresh() = guarded("refresh", "Setup details could not be read.") {
        mutableState.update { it.copy(restrictedSettings = null, permissions = PermissionRow.unknown()) }
        accept(status.current())
        when (val result = permissions.read()) {
            is PortResult.Success -> mutableState.update { it.copy(permissions = PermissionRow.from(result.value)) }
            is PortResult.Failure -> failed("permissions", "Permission states could not be read.", result.failure.cause)
        }
        when (val result = restrictedSettings.read()) {
            is PortResult.Success -> mutableState.update { it.copy(restrictedSettings = result.value) }
            is PortResult.Failure -> failed("restrictedSettings", "Restricted settings could not be read.", result.failure.cause)
        }
    }

    suspend fun saveRelayUrl(raw: String) = guarded("saveRelayUrl", "The relay URL could not be saved.") {
        val normalized = try {
            normalizeRelayUrl(raw)
        } catch (cause: IllegalArgumentException) {
            failed("parseRelayUrl", cause.message ?: "The relay URL is invalid.", cause)
            return@guarded
        }
        when (val result = settings.saveRelayUrl(normalized)) {
            is PortResult.Success -> {
                mutableState.update {
                    it.copy(relayUrl = normalized, encryptionWarning = encryptionWarning(normalized), error = null)
                }
                applyRelayUrl()
            }
            is PortResult.Failure -> failed("saveRelayUrl", "The relay URL could not be saved.", result.failure.cause)
        }
    }

    private suspend fun applyRelayUrl() {
        val message = "The relay URL was saved but the link could not switch to it."
        guarded("applyRelayUrl", message) {
            when (val result = connection.applyRelayUrl()) {
                is PortResult.Success -> Unit
                is PortResult.Failure -> failed("applyRelayUrl", message, result.failure.cause)
            }
        }
    }

    suspend fun connect() = action("connect", "Could not connect.") { connection.connect() }
    suspend fun pair(raw: String) = action("pair", "Could not begin pairing.") { connection.pair(raw) }

    fun offerPairingLink(uri: String) {
        val offer = try { PairingLink.parse(uri) }
        catch (cause: IllegalArgumentException) {
            failed("pairingLink", requireNotNull(cause.message), cause)
            return
        }
        mutableState.update { it.copy(pendingPairing = PendingPairing(offer.relayUrl, offer.code), error = null) }
    }

    fun dismissPairingLink() { mutableState.update { it.copy(pendingPairing = null) } }

    suspend fun confirmPairingLink() = pairingConfirmation.withLock {
        val pending = state.value.pendingPairing ?: return@withLock
        guarded("confirmPairingLink", "Pairing could not be started.") {
            when (val result = settings.saveRelayUrl(pending.relayUrl)) {
                is PortResult.Failure -> failed("saveRelayUrl", "The relay URL could not be saved.", result.failure.cause)
                is PortResult.Success -> {
                    mutableState.update { it.copy(relayUrl = pending.relayUrl,
                        encryptionWarning = encryptionWarning(pending.relayUrl), pendingPairing = null, error = null) }
                    when (val paired = connection.pair(pending.code)) {
                        is PortResult.Success -> Unit
                        is PortResult.Failure -> failed("pair", "Could not begin pairing.", paired.failure.cause)
                    }
                }
            }
        }
    }
    suspend fun openAppInfo() = action("openAppInfo", "Could not open App info.") { actions.openAppInfo() }

    suspend fun setup(permission: Permission) = action("setup", "Could not open the requested settings.") {
        when (permission) {
            Permission.ACCESSIBILITY, Permission.BATTERY_EXEMPT,
            Permission.UNUSED_APP_RESTRICTIONS_DISABLED, Permission.BACKGROUND_LOCATION,
            Permission.NOTIFICATION_ACCESS, Permission.ALL_FILES ->
                actions.openPermissionSettings(permission)
            else -> actions.requestPermission(permission)
        }
    }

    private suspend fun action(operation: String, message: String, call: suspend () -> PortResult<Unit>) =
        guarded(operation, message) {
            when (val result = call()) {
                is PortResult.Success -> {
                    mutableState.update { it.copy(error = null) }
                    refresh()
                }
                is PortResult.Failure -> failed(operation, message, result.failure.cause)
            }
        }

    private fun accept(result: PortResult<StatusSnapshot>) {
        when (result) {
            is PortResult.Failure -> failed("status", "Connection state could not be read.", result.failure.cause)
            is PortResult.Success -> present(result.value)
        }
    }

    private fun present(snapshot: StatusSnapshot) {
        val expiry = when (val grant = snapshot.bridgeGrant) {
            GrantExpiry.Missing -> null
            GrantExpiry.Never -> "never"
            is GrantExpiry.At -> formatExpiry(grant.epochSeconds)
        }
        val warning = when (snapshot.bridgeGrant) {
            GrantExpiry.Never -> null
            GrantExpiry.Missing -> "Grant missing; re-pair."
            is GrantExpiry.At -> null
        }
        mutableState.update { previous ->
            previous.copy(
                connectionText = wording(snapshot.connection), tone = tone(snapshot.connection), relayUrl = snapshot.relayUrl.orEmpty(),
                attemptedRelayUrl = snapshot.relayUrl,
                codeHint = if (snapshot.connection in setOf(ConnectionPhase.UNPAIRED, ConnectionPhase.PAIRING_REJECTED,
                    ConnectionPhase.SESSION_UNREADABLE, ConnectionPhase.REAUTH_REQUIRED))
                    "Not paired. Run the pair command on your Mac and enter the code here, or open the link it shows." else null,
                deviceId = snapshot.deviceId, grantExpiry = expiry, grantWarning = warning,
                encryptionWarning = snapshot.relayUrl?.let(::encryptionWarning),
                appVersion = snapshot.appVersion, retry = snapshot.connection == ConnectionPhase.REAUTH_REQUIRED,
                error = null,
            )
        }
        snapshot.failure?.let { failed("status", failureMessage(snapshot), it.cause) }
    }

    private fun failureMessage(snapshot: StatusSnapshot): String = when (snapshot.failureOrigin) {
        ConnectionFailureOrigin.TRANSPORT -> {
            if (!snapshot.retryScheduled && snapshot.connection != ConnectionPhase.CONNECTING) "The relay connection failed."
            else (snapshot.relayUrl?.let { "Can't reach $it." } ?: "The relay could not be reached.") + " Retrying."
        }
        ConnectionFailureOrigin.CONFIGURATION -> if (snapshot.failure?.cause?.message == "The pairing code must be six letters or digits.")
            "The pairing code must be six letters or digits." else "The relay address or connection settings could not be read."
        ConnectionFailureOrigin.SESSION -> "The stored session could not be read or saved."
        ConnectionFailureOrigin.AUTHENTICATION -> "Sign-in failed." + if (snapshot.retryScheduled) " Retrying." else ""
        ConnectionFailureOrigin.INTERNAL, ConnectionFailureOrigin.NONE -> "Connection details could not be read."
    }

    private fun tone(phase: ConnectionPhase): StatusTone = when (phase) {
        ConnectionPhase.ONLINE -> StatusTone.ONLINE
        ConnectionPhase.CONNECTING, ConnectionPhase.AUTHENTICATING, ConnectionPhase.BACKOFF -> StatusTone.WORKING
        ConnectionPhase.UNPAIRED, ConnectionPhase.REAUTH_REQUIRED, ConnectionPhase.PAIRING_REJECTED,
        ConnectionPhase.SESSION_UNREADABLE -> StatusTone.NEEDS_YOU
    }

    private fun wording(phase: ConnectionPhase): String = when (phase) {
        ConnectionPhase.UNPAIRED -> "Not paired"
        ConnectionPhase.CONNECTING -> "Connecting"
        ConnectionPhase.AUTHENTICATING -> "Signing in"
        ConnectionPhase.ONLINE -> "Connected"
        ConnectionPhase.BACKOFF -> "Waiting to reconnect"
        ConnectionPhase.REAUTH_REQUIRED -> "re-pair needed"
        ConnectionPhase.PAIRING_REJECTED -> "Pairing rejected. Check the code and try again."
        ConnectionPhase.SESSION_UNREADABLE -> "The stored session cannot be read. Enter a new code from your Mac."
    }

    private fun encryptionWarning(url: String): String? =
        if (url.startsWith("ws://", true) || url.startsWith("http://", true)) "Connection is not encrypted" else null

    private suspend fun guarded(operation: String, message: String, block: suspend () -> Unit) {
        try {
            withTimeout(25_000) { block() }
        } catch (cause: TimeoutCancellationException) {
            failed(operation, "The setup operation timed out.", cause)
        } catch (cause: CancellationException) {
            throw cause
        } catch (cause: Exception) {
            failed(operation, message, cause)
        }
    }

    private fun failed(operation: String, message: String, cause: Throwable) {
        logger.error("StatusPresenter", operation, "state=failed", FailureDiagnostics.sanitized(cause))
        mutableState.update { it.copy(error = message) }
    }
}
