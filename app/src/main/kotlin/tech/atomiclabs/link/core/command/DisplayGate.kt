// forge-allow: no-test declarations only
package tech.atomiclabs.link.core.command

/** Prepares the display, returning wake_failed/keyguard_blocked or an explicit outside-call failure. */
fun interface DisplayGate { suspend fun prepare(): DisplayGateResult }

sealed interface DisplayGateResult {
    data object Ready : DisplayGateResult
    data class Failed(val failure: CommandResult.Failure) : DisplayGateResult
}
