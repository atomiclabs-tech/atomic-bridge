// forge-allow: no-test declarations only
package tech.atomiclabs.link.core.port

/** Epoch time is for payloads, monotonic time for elapsed work; sleep must suspend and be cancellable. */
interface Clock {
    fun epochMs(): Long
    fun monotonicMs(): Long
    suspend fun sleep(durationMs: Long)
}
