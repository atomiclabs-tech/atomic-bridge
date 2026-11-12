package tech.atomiclabs.link.core.input

import tech.atomiclabs.link.core.command.*
import tech.atomiclabs.link.core.log.Logger
import tech.atomiclabs.link.core.port.*
import tech.atomiclabs.link.core.screen.ScreenReadException

class InputRoutes(
    private val taps: TapCommands, private val text: TextEntry, private val geometry: SwipeGeometry,
    private val scroll: ScrollCommand, private val keys: KeyCommands, private val wait: WaitCommand,
    private val gestures: GestureInput, private val logger: Logger,
) : RouteGroup {
    override val routes = listOf(
        route("/tap") { taps.tap(it) }, route("/long_press") { taps.tap(it, true) },
        route("/tap_text", handle = taps::tapText), route("/type", handle = text::type),
        route("/swipe") { move(geometry.swipe(it)) }, route("/drag") { move(geometry.drag(it), drag = true) },
        route("/scroll", handle = scroll::execute), route("/press_key", keys::needs, keys::execute),
        route("/wait", { if (WaitCommand.searching(it)) RouteNeeds(true, true) else RouteNeeds() }, wait::execute),
    )

    private fun route(path: String, needs: (BodyParams) -> RouteNeeds = { RouteNeeds(true, true) },
        handle: suspend (BodyParams) -> CommandResult): Route = Route("POST", path, needs) { body ->
        try { handle(body) }
        catch (cause: InputFailure) {
            if (cause.cause == null) logger.warn("InputRoutes", path, "state=${cause.result.code.wireValue}")
            else logger.error("InputRoutes", path, "state=${cause.result.code.wireValue}", FailureDiagnostics.sanitized(cause))
            cause.result
        } catch (cause: ScreenReadException) {
            logger.error("InputRoutes", path, "state=tree_failed", FailureDiagnostics.sanitized(cause))
            CommandResult.Failure(ErrorCode.INTERNAL_ERROR, "The accessibility tree could not be read.")
        }
    }

    private suspend fun move(stroke: GestureStroke, drag: Boolean = false): CommandResult {
        gestures.dispatch(listOf(stroke)).inputValue(ErrorCode.GESTURE_FAILED, "The gesture failed or was cancelled.")
        return CommandResult.Success(if (drag) geometry.dragFields(stroke) else geometry.fields(stroke))
    }
}

internal class InputFailure(val result: CommandResult.Failure, cause: Throwable? = null) :
    IllegalStateException("Input command failed.", cause)

internal fun inputFail(code: ErrorCode, message: String): Nothing = throw InputFailure(CommandResult.Failure(code, message))

internal fun <T> PortResult<T>.inputValue(code: ErrorCode, message: String): T = when (this) {
    is PortResult.Success -> value
    is PortResult.Failure -> throw InputFailure(CommandResult.Failure(code, message), failure.cause)
}

internal fun ReadValue<UiNode>.inputNode(message: String): UiNode? = when (this) {
    is ReadValue.Present -> value
    ReadValue.Absent -> null
    is ReadValue.Unreadable -> throw InputFailure(CommandResult.Failure(ErrorCode.GESTURE_FAILED, message), failure.cause)
}
