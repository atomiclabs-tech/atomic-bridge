package tech.atomiclabs.link.core.command

class UnsupportedRoutes : RouteGroup {
    override val routes = listOf(Route("POST", "/return_to_hermes") {
        CommandResult.Failure(ErrorCode.UNSUPPORTED_COMMAND, "This command is unsupported.")
    })
}
