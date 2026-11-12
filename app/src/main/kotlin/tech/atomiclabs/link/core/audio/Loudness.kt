package tech.atomiclabs.link.core.audio

import kotlin.math.abs
import kotlin.math.sqrt

object Loudness {
    data class Measurement(val rms: Double, val peak: Double, val silent: Boolean)

    /** PCM16 little-endian values are normalized to full scale, including the negative endpoint. */
    fun measure(pcm: ByteArray, count: Int = pcm.size): Measurement {
        require(count in 0..pcm.size && count % 2 == 0) { "PCM16 must contain complete samples." }
        var squares = 0.0
        var peak = 0.0
        for (offset in 0 until count step 2) {
            val signed = ((pcm[offset].toInt() and 255) or (pcm[offset + 1].toInt() shl 8)).toShort()
            val value = signed / 32768.0
            squares += value * value
            peak = maxOf(peak, abs(value))
        }
        val rms = if (count == 0) 0.0 else sqrt(squares / (count / 2))
        return Measurement(rms, peak, rms < 0.02)
    }
}
