package tech.atomiclabs.link.core.command

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import tech.atomiclabs.link.core.log.Logger
import tech.atomiclabs.link.core.port.*

class CoreRoutes(
    private val tree: UiTreeSource,
    private val clock: Clock,
    private val openStatus: suspend () -> PortResult<Unit>,
    private val logger: Logger,
) : RouteGroup {
    override val routes = listOf(
        Route("GET", "/ping") { checked("ping") {
            val connected = value(tree.isConnected())
            CommandResult.Success(buildJsonObject {
                put("pong", true); put("ts", clock.epochMs()); put("accessibilityService", connected)
            })
        } },
        Route("POST", "/setup") { checked("setup") {
            value(openStatus())
            CommandResult.Success(buildJsonObject { put("ok", true) })
        } },
    )

    private fun <T> value(result: PortResult<T>): T = when (result) {
        is PortResult.Success -> result.value
        is PortResult.Failure -> throw IllegalStateException("The liveness operation failed.", result.failure.cause)
    }

    private suspend fun checked(operation: String, block: suspend () -> CommandResult): CommandResult = try {
        withTimeout(5_000) { block() }
    } catch (cause: CancellationException) {
        throw cause
    } catch (cause: Exception) {
        logger.error("CoreRoutes", operation, "state=failed", FailureDiagnostics.sanitized(cause))
        CommandResult.Failure(ErrorCode.INTERNAL_ERROR, "The liveness operation could not complete.")
    }
}
