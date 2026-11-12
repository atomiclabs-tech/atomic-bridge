// forge-allow: no-test declarations only
package tech.atomiclabs.link.core.port

/** Reports display interactivity and attempts a checked, bounded bright wake lock; never unlocks. */
interface PowerState {
    suspend fun isInteractive(): PortResult<Boolean>
    suspend fun wakeWithBrightLock(durationMs: Long): PortResult<Unit>
}

/** Reads both showing/secure states; read errors cannot masquerade as an unlocked phone. */
fun interface KeyguardState { suspend fun read(): PortResult<KeyguardSnapshot> }

/** Transparent activity jobs finish within 3 s; missing callbacks/refused launches return failures. */
interface Trampoline {
    suspend fun wakeAndDismiss(): PortResult<Unit>
    suspend fun readClipboard(): PortResult<String>
}

data class KeyguardSnapshot(val showing: Boolean, val secure: Boolean)
