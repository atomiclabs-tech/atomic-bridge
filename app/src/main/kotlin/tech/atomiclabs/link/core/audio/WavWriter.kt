package tech.atomiclabs.link.core.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder

object WavWriter {
    /** Header for mono 16 kHz PCM16; the payload length must be known before streaming the file. */
    fun header(bytes: Int): ByteArray {
        require(bytes >= 0 && bytes % 2 == 0 && bytes <= Int.MAX_VALUE - 36) { "Invalid PCM16 length." }
        return ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII)); putInt(36 + bytes)
            put("WAVEfmt ".toByteArray(Charsets.US_ASCII)); putInt(16)
            putShort(1); putShort(1); putInt(16_000); putInt(32_000)
            putShort(2); putShort(16); put("data".toByteArray(Charsets.US_ASCII)); putInt(bytes)
        }.array()
    }
}
