package tech.atomiclabs.link.core.protocol

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import tech.atomiclabs.link.core.command.FailureDiagnostics
import tech.atomiclabs.link.core.log.Logger

/** Malformed frames/commands are logged without payload diagnostics and dropped as null. */
class EnvelopeCodec(private val logger: Logger) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun decode(frame: String): Envelope? = try {
        json.decodeFromString<Envelope>(frame)
    } catch (cause: SerializationException) {
        malformed("decodeEnvelope", cause)
    } catch (cause: IllegalArgumentException) {
        malformed("decodeEnvelope", cause)
    }

    fun decodeCommand(envelope: Envelope): BridgeCommand? {
        if (envelope.channel != "bridge" || envelope.type != "bridge.command") return null
        return try {
            json.decodeFromJsonElement<BridgeCommand>(envelope.payload).also {
                require(it.requestId.isNotBlank() && it.method.isNotBlank() && it.path.isNotBlank()) {
                    "Command identity fields are required."
                }
            }
        } catch (cause: SerializationException) {
            malformed("decodeCommand", cause)
        } catch (cause: IllegalArgumentException) {
            malformed("decodeCommand", cause)
        }
    }

    fun encode(envelope: Envelope): String = json.encodeToString(envelope)

    fun commandEnvelope(command: BridgeCommand): Envelope = Envelope(
        "bridge", "bridge.command", command.requestId, json.encodeToJsonElement(command).jsonObject,
    )

    fun responseEnvelope(response: BridgeResponse, envelopeId: String): Envelope = Envelope(
        "bridge", "bridge.response", envelopeId, json.encodeToJsonElement(response).jsonObject,
    )

    private fun malformed(operation: String, cause: Exception): Nothing? {
        logger.error("EnvelopeCodec", operation, "state=malformed", FailureDiagnostics.sanitized(cause))
        return null
    }
}
