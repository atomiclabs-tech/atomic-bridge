// Ported from upstream app/src/main/kotlin/com/hermesandroid/relay/network/relay/BridgeCommandHandler.kt at commit da782a8.
package tech.atomiclabs.link.core.screen

import kotlinx.serialization.json.*
import tech.atomiclabs.link.core.command.*

class ScreenRoutes(
    private val windows: WindowOrder,
    private val walker: ScreenTreeWalker,
    private val search: NodeSearch,
    private val describer: NodeDescriber,
    private val hasher: ScreenHasher,
    private val activityForPackage: (String) -> String?,
) : RouteGroup {
    override val routes: List<Route> = listOf(
        Route("GET", "/screen", { RouteNeeds(true, true) }) { body ->
            val includeBounds = body.boolean("include_bounds", true)
            CommandResult.Success(walker.read(windows.snapshot()).toJson(includeBounds))
        },
        Route("POST", "/find_nodes", { RouteNeeds(true, true) }) { body -> find(body) },
        Route("POST", "/describe_node", { RouteNeeds(true, true) }) { body -> describe(body) },
        Route("GET", "/screen_hash", { RouteNeeds(true, true) }) { hash(null) },
        Route("POST", "/diff_screen", { RouteNeeds(true, true) }) { body -> hash(body.requiredString("previous_hash")) },
        Route("GET", "/current_app", { RouteNeeds(accessibility = true) }) {
            val packageName = windows.foregroundPackage()
            CommandResult.Success(buildJsonObject {
                put("package", packageName)
                put("activity", packageName?.let(activityForPackage))
            })
        },
    )

    private suspend fun find(body: BodyParams): CommandResult {
        val limit = body.int("limit", 20)
        if (limit !in 1..MAX_SCREEN_NODES) throw BadRequestException("limit")
        val text = body.string("text")
        val className = body.string("class_name")
        val clickable = body.boolean("clickable")
        val snapshot = windows.snapshot()
        val result = search.find(snapshot, text, className, clickable, limit)
        return CommandResult.Success(buildJsonObject {
            put("matches", JsonArray(result.nodes.map { it.toJson() }))
            put("count", result.nodes.size)
            put("packageName", snapshot.packageName)
            put("truncated", result.outcome.truncated)
            put("visited", result.outcome.visited)
        })
    }

    private suspend fun describe(body: BodyParams): CommandResult {
        val nodeId = body.requiredString("nodeId")
        val properties = describer.describe(windows.snapshot(), nodeId)
            ?: return CommandResult.Failure(ErrorCode.NODE_NOT_FOUND, "The node was not found on the current screen.")
        return CommandResult.Success(properties)
    }

    private suspend fun hash(previous: String?): CommandResult {
        val result = hasher.hash(windows.snapshot())
        return CommandResult.Success(buildJsonObject {
            put("hash", result.hash)
            put("node_count", result.nodeCount)
            put("truncated", result.truncated)
            if (previous != null) put("changed", result.hash != previous)
        })
    }
}
