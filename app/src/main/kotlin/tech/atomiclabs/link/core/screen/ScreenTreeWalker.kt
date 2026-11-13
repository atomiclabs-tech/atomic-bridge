// Ported from upstream app/src/main/kotlin/com/hermesandroid/relay/accessibility/ScreenReader.kt at commit da782a8.
package tech.atomiclabs.link.core.screen

import tech.atomiclabs.link.core.port.*

class ScreenTreeWalker(private val clock: Clock) {
    suspend fun read(snapshot: ScreenSnapshot): ScreenContent {
        val nodes = mutableListOf<ScreenNode>()
        visit(snapshot) { node ->
            nodes += ScreenNode(node.nodeId, node.properties)
            nodes.size < MAX_SCREEN_NODES
        }
        val bounds = snapshot.windows.map { it.properties.bounds }
            .filter { it.right > it.left && it.bottom > it.top }
        val union = if (bounds.isEmpty()) Bounds(0, 0, 0, 0) else Bounds(
            bounds.minOf { it.left }, bounds.minOf { it.top },
            bounds.maxOf { it.right }, bounds.maxOf { it.bottom },
        )
        return ScreenContent(snapshot, union, nodes, nodes.size >= MAX_SCREEN_NODES)
    }

    /** Finite searches also have a time bound; unbounded read/lookup/hash walks retain their own caps. */
    suspend fun visit(
        snapshot: ScreenSnapshot,
        maxVisits: Int = Int.MAX_VALUE,
        deadlineMs: Long? = if (maxVisits == Int.MAX_VALUE) null else clock.monotonicMs() + MAX_SEARCH_MS,
        includeContainers: Boolean = false,
        accept: suspend (LocatedNode) -> Boolean,
    ): VisitOutcome {
        var visited = 0
        for (entry in snapshot.windows) {
            var emitted = 0
            val pending = ArrayDeque<Pair<UiNode, UiNodeProperties?>>()
            pending.addLast(entry.root to entry.properties)
            while (pending.isNotEmpty()) {
                if (visited >= maxVisits || (deadlineMs != null && clock.monotonicMs() >= deadlineMs)) {
                    return VisitOutcome(visited, true)
                }
                val (node, cached) = pending.removeLast()
                val properties = cached ?: node.read().valueOrThrow()
                visited++
                if (deadlineMs != null && clock.monotonicMs() >= deadlineMs) return VisitOutcome(visited, true)
                if (includeContainers || NodeFilter.emits(properties)) {
                    val located = LocatedNode("w${entry.properties.windowId}:${emitted++}", node, properties)
                    if (!accept(located)) return VisitOutcome(visited, false)
                }
                node.children().valueOrThrow().asReversed().forEach { pending.addLast(it to null) }
            }
        }
        return VisitOutcome(visited, false)
    }
}
