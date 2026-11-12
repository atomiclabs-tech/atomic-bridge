package tech.atomiclabs.link.core.connection

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import tech.atomiclabs.link.core.port.StoredSession

class AuthFramesTest {
    private val frames = AuthFrames { "frame-id" }
    private val previous = StoredSession("fixture-session", "fixture-refresh", null, mapOf("bridge" to null), 0)

    @Test fun pairingAndSessionCredentialsAreExclusiveWithAllIdentityFields() {
        val pairing = frames.auth("device-id", description(), previous, "fixture-code")
        assertEquals("system", pairing.channel)
        assertEquals("auth", pairing.type)
        assertTrue(pairing.payload["pairing_code"]!!.jsonPrimitive.content == "fixture-code")
        assertFalse(pairing.payload.containsKey("session_token"))
        assertFalse(pairing.payload.containsKey("refresh_token"))
        val session = frames.auth("device-id", description(), previous, null).payload
        assertTrue(session["session_token"]!!.jsonPrimitive.content == previous.sessionToken)
        assertTrue(session["refresh_token"]!!.jsonPrimitive.content == previous.refreshToken)
        assertFalse(session.containsKey("pairing_code"))
        assertEquals(setOf("session_token", "refresh_token", "device_id", "device_name", "device_hostname",
            "device_model", "device_platform", "client_surface", "device_form_factor"), session.keys)
        assertEquals("android", session["client_surface"]!!.jsonPrimitive.content)
        assertEquals("phone", session["device_form_factor"]!!.jsonPrimitive.content)
    }

    @Test fun acceptedTokenAlwaysReplacesTheSentOneAndAbsentRefreshIsPreserved() {
        val accepted = frames.accepted(buildJsonObject {
            put("session_token", "fixture-replacement"); put("expires_at", 1234)
            put("grants", buildJsonObject { put("bridge", JsonNull); put("other", 100) })
        }, previous, 456)
        assertTrue(accepted.sessionToken == "fixture-replacement")
        assertTrue(accepted.refreshToken == previous.refreshToken)
        assertEquals(1234L, accepted.expiresAtEpochSeconds)
        assertEquals(mapOf("bridge" to null, "other" to 100L), accepted.grants)
        assertEquals(456L, accepted.savedAtEpochMs)
        val rotated = frames.accepted(buildJsonObject {
            put("session_token", "fixture-replacement"); put("refresh_token", "fixture-refresh-rotated")
        }, previous, 0)
        assertTrue(rotated.refreshToken == "fixture-refresh-rotated")
        assertNull(rotated.expiresAtEpochSeconds)
        assertTrue(rotated.grants.isEmpty())
    }

    @Test fun malformedCredentialsAndEpochsFailWithoutEchoingValues() {
        for (payload in listOf(buildJsonObject {}, buildJsonObject { put("session_token", "") },
            buildJsonObject { put("session_token", "fixture-session"); put("expires_at", "private-invalid") },
            buildJsonObject { put("session_token", "fixture-session"); put("grants", "private-invalid") })) {
            val failure = assertThrows(IllegalArgumentException::class.java) { frames.accepted(payload, previous, 0) }
            assertFalse(failure.stackTraceToString().contains("fixture-session"))
            assertFalse(failure.stackTraceToString().contains("private-invalid"))
        }
        assertThrows(IllegalArgumentException::class.java) { frames.auth("device", description(), null, null) }
    }

    @Test fun timeoutReasonsAreRecognizedWithoutReturningRemoteText() {
        for (reason in listOf("timeout", "AUTH TIMED OUT", "upstream timeout expired")) {
            assertTrue(frames.timedOut(buildJsonObject { put("reason", reason) }))
        }
        assertFalse(frames.timedOut(buildJsonObject { put("reason", "rejected") }))
    }
}
