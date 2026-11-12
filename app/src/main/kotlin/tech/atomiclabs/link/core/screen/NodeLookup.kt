// Ported from upstream app/src/main/kotlin/com/hermesandroid/relay/accessibility/ScreenReader.kt at commit da782a8.
package tech.atomiclabs.link.core.screen

class NodeLookup(private val walker: ScreenTreeWalker) {
    suspend fun find(snapshot: ScreenSnapshot, nodeId: String): LocatedNode? {
        if (!Regex("w-?\\d+:\\d+").matches(nodeId)) return null
        var found: LocatedNode? = null
        walker.visit(snapshot) { node ->
            if (node.nodeId == nodeId) found = node
            found == null
        }
        return found
    }
}
