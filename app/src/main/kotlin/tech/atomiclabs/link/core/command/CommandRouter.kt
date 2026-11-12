package tech.atomiclabs.link.core.command

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import tech.atomiclabs.link.core.log.Logger
import tech.atomiclabs.link.core.port.PortResult
import tech.atomiclabs.link.core.port.UiTreeSource
import tech.atomiclabs.link.core.protocol.BridgeCommand
import tech.atomiclabs.link.core.protocol.BridgeResponse

/** Per-command failure boundary; display work uses one fair mutex and requests never run twice. */
class CommandRouter(
    groups: List<RouteGroup>,
    private val tree: UiTreeSource,
    private val displayGate: DisplayGate,
    private val logger: Logger,
    private val deduper: RequestDeduper = RequestDeduper(),
    private val deadline: CommandDeadline = CommandDeadline(),
    routeDeadlines: Map<Pair<String, String>, CommandDeadline> = emptyMap(),
) {
    private val display = Mutex()
    private val routes = groups.flatMap { it.routes }.associateBy { it.method to it.path }
    private val routeDeadlines = routeDeadlines.toMap()

    init {
        require(routes.size == groups.sumOf { it.routes.size }) { "Route method/path registrations must be unique." }
    }

    suspend fun dispatch(command: BridgeCommand): BridgeResponse = when (val ticket = deduper.reserve(command.requestId)) {
        is RequestDeduper.Ticket.Replay -> ticket.response
        is RequestDeduper.Ticket.Pending -> ticket.response.await()
        is RequestDeduper.Ticket.Owner -> {
            val response = handle(command)
            // A cancelled caller must still record the outcome so a retry cannot repeat a side effect.
            withContext(NonCancellable) { deduper.complete(ticket, response) }
            response
        }
    }

    private suspend fun handle(command: BridgeCommand): BridgeResponse {
        val route = routes[command.method to command.path]
        val context = FailureDiagnostics.requestContext(command.requestId, route?.path)
        val result = try {
            val selectedDeadline = routeDeadlines[command.method to command.path] ?: deadline
            selectedDeadline.run { execute(route, BodyParams(command.body), context).toJson() }
        } catch (cause: BadRequestException) {
            logger.error("CommandRouter", "parameters", context, FailureDiagnostics.sanitized(cause))
            CommandResult.Failure(ErrorCode.BAD_REQUEST, cause.message!!).toJson()
        } catch (cause: TimeoutCancellationException) {
            logger.error("CommandRouter", "deadline", context, FailureDiagnostics.sanitized(cause))
            CommandResult.Failure(ErrorCode.TIMEOUT, "The command exceeded its deadline.").toJson()
        } catch (cause: Throwable) {
            logger.error("CommandRouter", "dispatch", context, FailureDiagnostics.sanitized(cause))
            CommandResult.Failure(ErrorCode.INTERNAL_ERROR, "The command failed unexpectedly.").toJson()
        }
        return BridgeResponse(command.requestId, result)
    }

    private suspend fun execute(route: Route?, body: BodyParams, context: String): CommandResult {
        if (route == null) return CommandResult.Failure(ErrorCode.UNKNOWN_COMMAND, "The command path or method is unknown.")
        val needs = route.needs(body)
        if (needs.display) return display.withLock { checkedHandle(route, body, needs, context) }
        return checkedHandle(route, body, needs, context)
    }

    private suspend fun checkedHandle(
        route: Route,
        body: BodyParams,
        needs: RouteNeeds,
        context: String,
    ): CommandResult {
        if (needs.accessibility) {
            when (val connected = tree.isConnected()) {
                is PortResult.Failure -> {
                    logger.error("CommandRouter", "accessibilityState", context,
                        FailureDiagnostics.sanitized(connected.failure.cause))
                    return CommandResult.Failure(ErrorCode.INTERNAL_ERROR, "Accessibility state could not be read.")
                }
                is PortResult.Success -> if (!connected.value) {
                    return CommandResult.Failure(ErrorCode.ACCESSIBILITY_DISABLED, "The accessibility service is disconnected.")
                }
            }
        }
        if (!needs.display) return route.handle(body)
        return when (val gate = displayGate.prepare()) {
            DisplayGateResult.Ready -> route.handle(body)
            is DisplayGateResult.Failed -> gate.failure
        }
    }
}
