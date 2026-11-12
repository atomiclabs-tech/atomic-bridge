// Adapted from upstream app/src/main/kotlin/com/hermesandroid/relay/accessibility/ActionExecutor.kt at da782a8.
package tech.atomiclabs.link.core.phone

import kotlinx.coroutines.*
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import tech.atomiclabs.link.core.command.*
import tech.atomiclabs.link.core.log.Logger
import tech.atomiclabs.link.core.port.*

class ClipboardRoutes(private val clipboard: ClipboardAccess, private val logger: Logger) : RouteGroup {
    override val routes = listOf(
        Route("GET", "/clipboard", { RouteNeeds(display = true) }) {
            checked("read") {
                val text = value(clipboard.read())
                CommandResult.Success(buildJsonObject { put("text", text) })
            }
        },
        Route("POST", "/clipboard") { body ->
            val text = body.requiredString("text")
            checked("write") {
                value(clipboard.write(text))
                CommandResult.Success(buildJsonObject { put("length", text.length) })
            }
        },
    )

    private fun <T> value(result: PortResult<T>): T = when (result) {
        is PortResult.Success -> result.value
        is PortResult.Failure -> throw IllegalStateException("Clipboard operation failed.", result.failure.cause)
    }

    private suspend fun checked(operation: String, block: suspend () -> CommandResult): CommandResult = try {
        withTimeout(5_000) { block() }
    } catch (cause: TimeoutCancellationException) {
        failed(operation, cause)
    } catch (cause: CancellationException) {
        throw cause
    } catch (cause: Exception) {
        failed(operation, cause)
    }

    private fun failed(operation: String, cause: Throwable): CommandResult.Failure {
        logger.error("ClipboardRoutes", operation, "state=failed", FailureDiagnostics.sanitized(cause))
        return CommandResult.Failure(ErrorCode.CLIPBOARD_UNAVAILABLE, "The clipboard is unavailable.")
    }
}
