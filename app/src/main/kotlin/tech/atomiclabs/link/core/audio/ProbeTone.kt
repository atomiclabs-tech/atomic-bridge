package tech.atomiclabs.link.core.audio

import kotlin.math.PI
import kotlin.math.sin

object ProbeTone {
    fun pcm(frequencyHz: Int = 1_000, durationMs: Long = 1_500): ByteArray {
        require(frequencyHz in 1..7_999 && durationMs in 1..5_000) { "Invalid probe tone parameters." }
        val frames = (durationMs * 16).toInt()
        return ByteArray(frames * 2).also { bytes ->
            for (frame in 0 until frames) {
                val value = (sin(2 * PI * frequencyHz * frame / 16_000) * 8_192).toInt()
                bytes[frame * 2] = value.toByte(); bytes[frame * 2 + 1] = (value shr 8).toByte()
            }
        }
    }
}
