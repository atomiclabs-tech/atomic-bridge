package tech.atomiclabs.link.core.setup

import tech.atomiclabs.link.core.port.RestrictedSettingsStatus

/** The four Workshop status words: Online / Connecting / Needs you / Off. */
enum class StatusTone { ONLINE, WORKING, NEEDS_YOU, OFF }

data class StatusViewState(
    val connectionText: String = "Reading connection state",
    val tone: StatusTone = StatusTone.WORKING,
    val relayUrl: String = "",
    val attemptedRelayUrl: String? = null,
    val pendingPairing: PendingPairing? = null,
    val codeHint: String? = null,
    val deviceId: String? = null,
    val grantExpiry: String? = null,
    val grantWarning: String? = null,
    val encryptionWarning: String? = null,
    val permissions: List<PermissionRow> = PermissionRow.unknown(),
    val restrictedSettings: RestrictedSettingsStatus? = null,
    val appVersion: String = "",
    val retry: Boolean = false,
    val error: String? = null,
) {
    // View data can contain a pending code or rejected URL; diagnostics must never serialize it.
    override fun toString(): String = "StatusViewState(private display values redacted)"
}

/** Unconfirmed credentials stay in memory and never appear in diagnostics. */
data class PendingPairing(val relayUrl: String, val code: String) {
    override fun toString(): String = "PendingPairing(private values redacted)"
}
