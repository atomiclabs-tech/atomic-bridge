package tech.atomiclabs.link.core.connection

import org.junit.Assert.*
import org.junit.Test

class ReconnectPolicyTest {
    @Test fun jitterIncludesBothBoundsAndCapsAt60SecondsPastAThousandFailures() {
        val low = ReconnectPolicy { 0 }
        val high = ReconnectPolicy { it - 1 }
        val caps = listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 32_000L, 60_000L)
        repeat(1_100) { index ->
            val cap = caps[index.coerceAtMost(caps.lastIndex)]
            assertEquals(cap / 2, low.failed())
            assertEquals(cap, high.failed())
        }
        assertEquals(1_100L, low.attempt)
        assertEquals(60_000L, high.delayFor(Long.MAX_VALUE))
    }

    @Test fun resetRequiresThirtySecondsOnlineAndSpecialWaitsMatchR17() {
        val policy = ReconnectPolicy { 0 }
        policy.failed(); policy.failed()
        policy.onlineFor(29_999)
        assertEquals(2L, policy.attempt)
        policy.onlineFor(30_000)
        assertEquals(0L, policy.attempt)
        assertEquals(500L, policy.failed())
        assertEquals(10_000L, ReconnectPolicy.NETWORK_WAIT_MS)
        assertEquals(300_000L, ReconnectPolicy.RATE_LIMIT_MS)
        assertEquals(300_000L, ReconnectPolicy.REAUTH_WAIT_MS)
        assertEquals(3, ReconnectPolicy.MAX_AUTH_RETRIES)
    }

    @Test fun badAttemptsAndRandomOffsetsAreErrors() {
        assertThrows(IllegalArgumentException::class.java) { ReconnectPolicy().delayFor(0) }
        assertThrows(IllegalArgumentException::class.java) { ReconnectPolicy { -1 }.failed() }
        assertThrows(IllegalArgumentException::class.java) { ReconnectPolicy { it }.failed() }
    }
}
