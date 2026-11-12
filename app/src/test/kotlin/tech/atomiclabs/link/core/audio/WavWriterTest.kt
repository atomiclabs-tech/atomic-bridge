package tech.atomiclabs.link.core.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class WavWriterTest {
    @Test fun validHeaderDescribesExactlyMono16kPcm16AndPayloadLength() {
        val bytes = WavWriter.header(32_000)
        val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(44, bytes.size)
        assertEquals("RIFF", String(bytes, 0, 4, Charsets.US_ASCII)); assertEquals(32_036, header.getInt(4))
        assertEquals("WAVEfmt ", String(bytes, 8, 8, Charsets.US_ASCII)); assertEquals(16, header.getInt(16))
        assertEquals(1, header.getShort(20).toInt()); assertEquals(1, header.getShort(22).toInt())
        assertEquals(16_000, header.getInt(24)); assertEquals(32_000, header.getInt(28))
        assertEquals(2, header.getShort(32).toInt()); assertEquals(16, header.getShort(34).toInt())
        assertEquals("data", String(bytes, 36, 4, Charsets.US_ASCII)); assertEquals(32_000, header.getInt(40))
        assertThrows(IllegalArgumentException::class.java) { WavWriter.header(3) }
        assertThrows(IllegalArgumentException::class.java) { WavWriter.header(-2) }
    }
}
