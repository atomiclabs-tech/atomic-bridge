package tech.atomiclabs.link.core.capture

import tech.atomiclabs.link.core.command.Route
import tech.atomiclabs.link.core.command.RouteGroup
import tech.atomiclabs.link.core.command.RouteNeeds

class ScreenshotRoutes(command: ScreenshotCommand, httpBase: suspend () -> String) : RouteGroup {
    override val routes = listOf(Route("GET", "/screenshot", { RouteNeeds(true, true) }) {
        command.execute(httpBase())
    })
}
