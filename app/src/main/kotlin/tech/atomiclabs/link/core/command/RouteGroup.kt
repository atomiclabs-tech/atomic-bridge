// forge-allow: no-test declarations only
package tech.atomiclabs.link.core.command

/** A feature exports routes with their handlers; only the composition root registers groups. */
interface RouteGroup { val routes: List<Route> }
