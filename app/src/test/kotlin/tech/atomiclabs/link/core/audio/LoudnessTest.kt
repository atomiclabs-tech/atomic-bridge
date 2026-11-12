package tech.atomiclabs.link.core.audio

import org.junit.Assert.*
import org.junit.Test

class LoudnessTest {
    @Test fun measuresSilenceSineAndNegativeFullScale() {
        assertEquals(Loudness.Measurement(0.0, 0.0, true), Loudness.measure(ByteArray(320)))
        val sine = Loudness.measure(ProbeTone.pcm())
        assertEquals(0.25 / kotlin.math.sqrt(2.0), sine.rms, 0.0001)
        assertEquals(0.25, sine.peak, 0.0001); assertFalse(sine.silent)
        assertEquals(Loudness.Measurement(1.0, 1.0, false), Loudness.measure(byteArrayOf(0, -128)))
        assertTrue(Loudness.measure(byteArrayOf(0, 1)).silent)
        assertFalse(Loudness.measure(byteArrayOf(0, 3)).silent)
        assertThrows(IllegalArgumentException::class.java) { Loudness.measure(ByteArray(3)) }
    }
}
