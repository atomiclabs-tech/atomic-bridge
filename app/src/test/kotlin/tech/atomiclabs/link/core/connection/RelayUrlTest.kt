package tech.atomiclabs.link.core.connection

import org.junit.Assert.*
import org.junit.Test

class RelayUrlTest {
    @Test fun acceptsAllSchemesAndDerivesMatchingHttpAndWebsocketBases() {
        for (scheme in listOf("ws", "http", "wss", "https")) {
            val encrypted = scheme == "wss" || scheme == "https"
            val value = RelayUrl.parse("$scheme://192.168.1.10:8443/base")
            assertEquals("${if (encrypted) "wss" else "ws"}://192.168.1.10:8443/base/ws", value.websocketUrl)
            assertEquals("${if (encrypted) "https" else "http"}://192.168.1.10:8443/base", value.httpBase)
            assertEquals(encrypted, value.encrypted)
        }
    }

    @Test fun stripsTrailingEndpointsAndSlashesWithoutChangingTheBasePath() {
        for (tail in listOf("", "/", "/ws", "/ws/", "/health", "/health/")) {
            assertEquals("wss://relay.example/base/ws", RelayUrl.parse("https://relay.example/base$tail").websocketUrl)
        }
        assertEquals("http://[::1]:8080", RelayUrl.parse("ws://[::1]:8080/ws").httpBase)
    }

    @Test fun rejectsInvalidComponentsWithoutPuttingTheInputIntoTheException() {
        for (input in listOf("", "ftp://relay.example", "https://user:private@relay", "https://relay.example?q=private",
            "https://relay.example#private", "https://", "https://relay.example:0", "https://relay.example:65536",
            "https://relay.example:abc", "https://relay.example/a/../private", "https://relay.example\\private")) {
            val error = assertThrows(IllegalArgumentException::class.java) { RelayUrl.parse(input) }
            assertEquals("The relay URL is invalid.", error.message)
        }
    }

    @Test fun publicHostnameCleartextAttackIsRefusedWithFixedText() {
        assertCleartextRefused("ws://relay.example.com")
    }

    @Test fun publicIpv4CleartextAttackIsRefusedWithFixedText() {
        assertCleartextRefused("ws://203.0.113.5")
    }

    @Test fun localHostnameCleartextAttackIsRefusedWithFixedText() {
        assertCleartextRefused("http://phone.local")
    }

    @Test fun privateCleartextAttackAddressesRemainUsable() {
        for (input in listOf("ws://192.168.1.10:8767", "ws://100.100.1.2", "ws://localhost:8767",
            "ws://[fd00::1]:8767", "http://[::1]")) {
            assertFalse(RelayUrl.parse(input).encrypted)
        }
    }

    @Test fun allPrivateIpv4RangesIncludeTheirBoundaries() {
        val hosts = listOf("127.0.0.0", "127.255.255.255", "10.0.0.0", "10.255.255.255",
            "172.16.0.0", "172.31.255.255", "192.168.0.0", "192.168.255.255",
            "100.64.0.0", "100.127.255.255", "169.254.0.0", "169.254.255.255", "LOCALHOST")
        for (scheme in listOf("ws", "http")) for (host in hosts) {
            assertFalse(RelayUrl.parse("$scheme://$host").encrypted)
        }
    }

    @Test fun adjacentPublicIpv4RangesAndAmbiguousFormsAreRefused() {
        for (host in listOf("126.255.255.255", "128.0.0.0", "9.255.255.255", "11.0.0.0",
            "172.15.255.255", "172.32.0.0", "192.167.255.255", "192.169.0.0",
            "100.63.255.255", "100.128.0.0", "169.253.255.255", "169.255.0.0",
            "0.0.0.0", "255.255.255.255", "127.1", "2130706433", "010.0.0.1", "192.168.1.256")) {
            assertCleartextRefused("http://$host")
        }
    }

    @Test fun ipv6PrivatePrefixesAndExpandedLoopbackAreAccepted() {
        for (host in listOf("::1", "0:0:0:0:0:0:0:1", "0000::0001", "fc00::", "fdff:ffff:ffff:ffff:ffff:ffff:ffff:ffff",
            "FE80::1", "febf::1")) {
            assertFalse(RelayUrl.parse("ws://[$host]").encrypted)
        }
    }

    @Test fun publicOrMalformedIpv6LiteralsAreRefused() {
        for (host in listOf("::", "2001:db8::1", "fbff::1", "fe00::1", "fe7f::1", "fec0::1",
            "fd00:::1", "fd00::1::2", "fd00:1", "fd000::1")) {
            assertCleartextRefused("http://[$host]")
        }
    }

    @Test fun encryptedSchemesStillAllowPublicHostsAndAddresses() {
        for (scheme in listOf("wss", "https")) for (host in listOf("relay.example.com", "203.0.113.5", "phone.local", "[2001:db8::1]")) {
            assertTrue(RelayUrl.parse("$scheme://$host").encrypted)
        }
    }

    @Test fun privatePolicyErrorNeverIncludesTheSuppliedEndpoint() {
        assertCleartextRefused("http://relay.example.com/private-input-fixture")
    }

    private fun assertCleartextRefused(input: String) {
        val error = assertThrows(IllegalArgumentException::class.java) { RelayUrl.parse(input) }
        assertEquals("Unencrypted relay addresses are allowed only for private network addresses. Use wss:// or https://.", error.message)
        assertFalse(error.message!!.contains(input))
    }
}
