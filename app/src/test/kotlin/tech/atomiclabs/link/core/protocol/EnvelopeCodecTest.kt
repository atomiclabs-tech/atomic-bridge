package tech.atomiclabs.link.core.protocol

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import tech.atomiclabs.link.core.command.CommandResult
import tech.atomiclabs.link.core.command.ErrorCode
import tech.atomiclabs.link.core.fakes.FakeLogger

class EnvelopeCodecTest {
    private val logger = FakeLogger()
    private val codec = EnvelopeCodec(logger)

    @Test fun bridgeCommandRoundTripsWithBodyAndParams() {
        val command = BridgeCommand("req", "GET", "/example", body = buildJsonObject { put("limit", "50") })
        val frame = codec.encode(codec.commandEnvelope(command))
        assertEquals(command, codec.decodeCommand(codec.decode(frame)!!))
        val json = Json.parseToJsonElement(frame).jsonObject
        assertEquals("bridge.command", json["type"]!!.jsonPrimitive.content)
        assertEquals("req", json["payload"]!!.jsonObject["request_id"]!!.jsonPrimitive.content)
        assertTrue(logger.entries.isEmpty())
    }

    @Test fun unknownEnvelopeAndCommandFieldsIncludingProfilesAreIgnored() {
        val frame = """{"channel":"bridge","type":"bridge.command","id":"e","profiles":[{}],"future":42,
            "payload":{"request_id":"r","method":"POST","path":"/example","body":{},"new":true}}"""
        assertEquals("r", codec.decodeCommand(codec.decode(frame)!!)!!.requestId)
        assertTrue(logger.entries.isEmpty())
    }

    @Test fun responseUsesTheWireNamesAndAnObjectResult() {
        val response = BridgeResponse("request", CommandResult.Failure(ErrorCode.BAD_REQUEST, "Missing value.").toJson())
        val envelope = codec.responseEnvelope(response, "envelope")
        assertEquals("bridge.response", envelope.type)
        assertEquals("envelope", envelope.id)
        val payload = codec.decode(codec.encode(envelope))!!.payload
        assertEquals("request", payload["request_id"]!!.jsonPrimitive.content)
        assertEquals(200, payload["status"]!!.jsonPrimitive.int)
        assertFalse(payload["result"]!!.jsonObject["success"]!!.jsonPrimitive.boolean)
    }

    @Test fun malformedJsonIsDroppedWithSanitizedThrowableAndStack() {
        val privateMarker = "private-parser-fixture"
        assertNull(codec.decode("""{"channel":"$privateMarker", "payload": ???}"""))
        val error = logger.entries.single()
        assertEquals("error", error.level)
        assertEquals("decodeEnvelope", error.operation)
        assertTrue(error.cause!!.stackTrace.isNotEmpty())
        assertFalse(error.cause.stackTraceToString().contains(privateMarker))
    }

    @Test fun wrongCommandShapeIsDroppedAndDoesNotLeakValueInParserDiagnostic() {
        val privateMarker = "private-body-fixture"
        val payload = buildJsonObject {
            put("request_id", "r"); put("method", "POST"); put("path", "/example"); put("body", privateMarker)
        }
        assertNull(codec.decodeCommand(Envelope("bridge", "bridge.command", "e", payload)))
        assertFalse(logger.entries.single().cause!!.stackTraceToString().contains(privateMarker))
    }

    @Test fun missingRequiredFieldsAndBlankCommandIdentityAreMalformed() {
        assertNull(codec.decode("""{"channel":"bridge"}"""))
        assertNull(codec.decodeCommand(codec.commandEnvelope(BridgeCommand("", "GET", "/example"))))
        assertEquals(2, logger.entries.size)
    }

    @Test fun unrelatedTrafficIsNotTreatedAsAMalformedCommand() {
        assertNull(codec.decodeCommand(Envelope("system", "ping", "e")))
        assertNull(codec.decodeCommand(Envelope("bridge", "future", "e")))
        assertTrue(logger.entries.isEmpty())
    }
}
