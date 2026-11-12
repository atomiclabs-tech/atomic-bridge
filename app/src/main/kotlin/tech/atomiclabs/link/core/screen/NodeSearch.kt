// Ported from upstream app/src/main/kotlin/com/hermesandroid/relay/accessibility/ScreenReader.kt at commit da782a8.
package tech.atomiclabs.link.core.screen

data class NodeSearchResult(val nodes: List<ScreenNode>, val outcome: VisitOutcome)

class NodeSearch(private val walker: ScreenTreeWalker) {
    suspend fun find(
        snapshot: ScreenSnapshot,
        text: String? = null,
        className: String? = null,
        clickable: Boolean? = null,
        limit: Int = 20,
    ): NodeSearchResult {
        require(limit in 1..MAX_SCREEN_NODES) { "Search limit is outside the supported range." }
        val matches = mutableListOf<ScreenNode>()
        val outcome = walker.visit(snapshot, MAX_SEARCH_VISITS) { located ->
            val node = located.properties
            val textMatches = text == null || TextMatch.matchesNode(node, text)
            if (textMatches && (className == null || node.className == className) &&
                (clickable == null || node.clickable == clickable)) {
                matches += ScreenNode(located.nodeId, node)
            }
            matches.size < limit
        }
        return NodeSearchResult(matches, outcome)
    }
}
