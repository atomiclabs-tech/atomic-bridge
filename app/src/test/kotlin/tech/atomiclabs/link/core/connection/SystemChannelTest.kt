package tech.atomiclabs.link.core.connection

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import tech.atomiclabs.link.core.fakes.*
import tech.atomiclabs.link.core.protocol.Envelope

class SystemChannelTest {
    @Test fun pingUsesClockAndNeverSendsBeforeAuthentication() = runTest {
        val logger = FakeLogger()
        val system = SystemChannel(FakeClock(testScheduler, 1234), logger) { "reply" }
        assertNull(system.handle(Envelope("system", "ping", "incoming"), false, emptyList()))
        val response = system.handle(Envelope("system", "ping", "incoming"), true, emptyList())!!
        assertEquals("system", response.channel)
        assertEquals("pong", response.type)
        assertEquals(1234L, response.payload["ts"]!!.jsonPrimitive.long)
        assertTrue(logger.entries.isEmpty())
    }

    @Test fun unrelatedAndUnknownTrafficIsIgnoredWithoutError() = runTest {
        val logger = FakeLogger()
        val system = SystemChannel(FakeClock(testScheduler), logger) { "reply" }
        for ((channel, type) in listOf("pairing" to "profiles.updated", "system" to "supervised.updated",
            "system" to "pong", "unknown" to "anything", "system" to "future")) {
            assertNull(system.handle(Envelope(channel, type, "id"), true, emptyList()))
        }
        assertTrue(logger.entries.isEmpty())
    }

    @Test fun remoteWarningRetainsMessageWithCredentialsRedactedAndMalformedMessageIsLogged() = runTest {
        val logger = FakeLogger()
        val system = SystemChannel(FakeClock(testScheduler), logger) { "reply" }
        val privateValues = listOf("fixture-session", "fixture-refresh", "fixture-code")
        system.handle(Envelope("system", "error", "id", buildJsonObject {
            put("message", "Rejected ${privateValues.joinToString(" ")}\nconnection")
        }), true, privateValues)
        val warning = logger.entries.single()
        assertEquals("warn", warning.level)
        assertTrue(warning.context.contains("Rejected"))
        privateValues.forEach { assertFalse(warning.toString().contains(it)) }
        assertFalse(warning.context.contains('\n'))
        system.handle(Envelope("system", "error", "id", buildJsonObject { put("message", 5) }), true, privateValues)
        assertEquals("error", logger.entries.last().level)
        assertNotNull(logger.entries.last().cause)
    }
}
