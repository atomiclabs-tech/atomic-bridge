// Adapted from upstream app/src/main/kotlin/com/hermesandroid/relay/accessibility/ActionExecutor.kt at da782a8.
package tech.atomiclabs.link.core.phone

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import tech.atomiclabs.link.core.command.*
import tech.atomiclabs.link.core.log.Logger
import tech.atomiclabs.link.core.port.*

class MediaKeyRoutes(private val media: MediaKeys, private val logger: Logger) : RouteGroup {
    override val routes = listOf(Route("POST", "/media") { body ->
        val name = body.requiredString("action")
        val action = when (name) {
            "play" -> MediaAction.PLAY
            "pause" -> MediaAction.PAUSE
            "toggle" -> MediaAction.TOGGLE
            "next" -> MediaAction.NEXT
            "previous" -> MediaAction.PREVIOUS
            else -> throw BadRequestException("action")
        }
        when (val result = media.send(action)) {
            is PortResult.Success -> CommandResult.Success(buildJsonObject {
                put("action", name); put("keycode", result.value)
            })
            is PortResult.Failure -> {
                logger.error("MediaKeyRoutes", "send", "state=failed", FailureDiagnostics.sanitized(result.failure.cause))
                CommandResult.Failure(when (result.failure.kind) {
                    FailureKind.PERMISSION_DENIED -> ErrorCode.PERMISSION_DENIED
                    FailureKind.TIMED_OUT -> ErrorCode.TIMEOUT
                    else -> ErrorCode.INTERNAL_ERROR
                }, "The media key could not be sent.")
            }
        }
    })
}
