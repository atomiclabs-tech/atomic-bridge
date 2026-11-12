package tech.atomiclabs.link.core.connection

import kotlin.random.Random

/** Transport failures keep retrying; auth/rate-limit waits are separate and cannot be network-shortened. */
class ReconnectPolicy(private val randomBelow: (Long) -> Long = { Random.nextLong(it) }) {
    var attempt: Long = 0
        private set

    fun failed(): Long {
        if (attempt < Long.MAX_VALUE) attempt++
        return delayFor(attempt)
    }

    fun delayFor(attempt: Long): Long {
        require(attempt > 0) { "A retry attempt must be positive." }
        val cap = (1_000L shl (attempt - 1).coerceAtMost(6).toInt()).coerceAtMost(60_000L)
        val half = cap / 2
        val jitter = randomBelow(half + 1)
        require(jitter in 0..half) { "The reconnect random source returned an invalid offset." }
        return half + jitter
    }

    fun onlineFor(elapsedMs: Long) {
        if (elapsedMs >= 30_000) attempt = 0
    }

    companion object {
        const val NETWORK_WAIT_MS = 10_000L
        const val RATE_LIMIT_MS = 300_000L
        const val REAUTH_WAIT_MS = 300_000L
        const val MAX_AUTH_RETRIES = 3
    }
}
