package tech.atomiclabs.link.core.setup

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import org.junit.Assert.*
import org.junit.Test

class PairingLinkTest {
    private fun code() = (0 until 6).map { (65 + it).toChar() }.joinToString("")
    private fun encode(value: String) = URLEncoder.encode(value, StandardCharsets.UTF_8.name())
    private fun link(relay: String = "wss://fixture.example/health", value: String = code().lowercase()) =
        "atomic://pair?relay=${encode(relay)}&code=${encode(value)}"

    @Test fun decodesRelayAndNormalizesSuppliedCodeWithoutExposingCredentialsInDiagnostics() {
        val raw = link()
        val parsed = PairingLink.parse(raw.replace("atomic:", "AtOmIc:"))
        assertEquals("https://fixture.example", parsed.relayUrl)
        assertTrue("The supplied code must be normalized", parsed.code == code())
        assertFalse(parsed.toString().contains(parsed.code))
        assertFalse(parsed.toString().contains(raw))
        assertEquals("http://192.168.20.30:1234", PairingLink.parse(link("ws://192.168.20.30:1234/ws")).relayUrl)
    }

    @Test fun refusesWrongDestinationPublicCleartextAndInvalidOrMissingPartsWithSafeSentences() {
        val raw = link()
        val invalid = listOf(raw.replace("atomic:", "https:"), raw.replace("://pair?", "://other?"),
            link("ws://public.example"), link(value = code().dropLast(1)), raw.substringBefore("&code="),
            raw + "&extra=value", raw + "&code=" + code(), raw + "#fragment", raw.replace("pair?", "pair/path?"),
            link(value = " " + code()), link(value = code().dropLast(1) + "!"),
            raw.replace("relay=", "relay=%"), "atomic://pair?code=${code()}")
        for (uri in invalid) {
            val failure = assertThrows(IllegalArgumentException::class.java) { PairingLink.parse(uri) }
            val diagnostic = failure.stackTraceToString()
            assertTrue(failure.message!!.endsWith('.'))
            assertFalse("The rejected link must remain private", diagnostic.contains(uri))
            assertFalse("The supplied code must remain private", diagnostic.contains(code()))
        }
    }
}
