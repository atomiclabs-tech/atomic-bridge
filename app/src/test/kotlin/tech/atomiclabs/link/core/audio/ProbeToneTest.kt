package tech.atomiclabs.link.core.audio

import org.junit.Assert.*
import org.junit.Test

class ProbeToneTest {
    @Test fun defaultToneIs1500msAndHasOneKHzPeriod() {
        val pcm = ProbeTone.pcm()
        assertEquals(48_000, pcm.size)
        for (offset in 0 until 32) assertEquals(pcm[offset], pcm[offset + 32])
        assertThrows(IllegalArgumentException::class.java) { ProbeTone.pcm(8_000) }
        assertThrows(IllegalArgumentException::class.java) { ProbeTone.pcm(durationMs = 10_000) }
    }
}
