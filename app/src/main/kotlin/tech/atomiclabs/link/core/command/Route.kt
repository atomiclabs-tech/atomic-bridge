// forge-allow: no-test declarations only
package tech.atomiclabs.link.core.command

/** Requirements are computed from body before accessibility then display preconditions run. */
data class Route(
    val method: String,
    val path: String,
    val needs: (BodyParams) -> RouteNeeds = { RouteNeeds() },
    val handle: suspend (BodyParams) -> CommandResult,
)

data class RouteNeeds(val accessibility: Boolean = false, val display: Boolean = false)
