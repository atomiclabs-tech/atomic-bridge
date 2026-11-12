// forge-allow: no-test declarations only
package tech.atomiclabs.link.core.port

/**
 * ALLOWED/RESTRICTED require an actual supported platform observation.
 * UNSUPPORTED explicitly means unknown, never granted; failed observations return PortResult.Failure
 * with the existing PortFailure and cause instead of inventing a status.
 */
fun interface RestrictedSettingsState {
    suspend fun read(): PortResult<RestrictedSettingsStatus>
}

/** UNSUPPORTED is an unknown observation, not evidence that restricted settings are allowed. */
enum class RestrictedSettingsStatus { ALLOWED, RESTRICTED, UNSUPPORTED }
