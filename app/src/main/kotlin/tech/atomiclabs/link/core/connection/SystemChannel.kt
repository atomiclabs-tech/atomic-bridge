package tech.atomiclabs.link.core.connection

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import tech.atomiclabs.link.core.command.BodyParams
import tech.atomiclabs.link.core.command.FailureDiagnostics
import tech.atomiclabs.link.core.log.Logger
import tech.atomiclabs.link.core.port.Clock
import tech.atomiclabs.link.core.protocol.Envelope

/** System traffic never bypasses auth ordering; known credentials are redacted from remote warnings. */
class SystemChannel(private val clock: Clock, private val logger: Logger, private val newId: () -> String) {
    fun handle(envelope: Envelope, authenticated: Boolean, secrets: Collection<String>): Envelope? {
        if (envelope.channel != "system") return null
        return when (envelope.type) {
            "ping" -> if (authenticated) Envelope("system", "pong", newId(), buildJsonObject {
                put("ts", clock.epochMs())
            }) else null
            "error" -> {
                try {
                    var message = BodyParams(envelope.payload).requiredString("message")
                    secrets.filter { it.isNotEmpty() }.sortedByDescending { it.length }.forEach {
                        message = message.replace(it, "<redacted>", ignoreCase = true)
                    }
                    message = message.map { if (it.isISOControl()) ' ' else it }.joinToString("").take(256)
                    logger.warn("SystemChannel", "remoteError", "state=${if (authenticated) "online" else "authenticating"} message=$message")
                } catch (cause: IllegalArgumentException) {
                    logger.error("SystemChannel", "remoteError", "state=malformed", FailureDiagnostics.sanitized(cause))
                }
                null
            }
            else -> null
        }
    }
}
