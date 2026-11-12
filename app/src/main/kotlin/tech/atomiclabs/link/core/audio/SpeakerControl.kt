package tech.atomiclabs.link.core.audio

import java.util.ArrayDeque
import tech.atomiclabs.link.core.port.*

/** Finds one visible, enabled Speaker action; callers verify the resulting audio route. */
class SpeakerControl(private val tree: UiTreeSource) {
    suspend fun click(): PortResult<Unit> {
        val windows = when (val result = tree.windows()) {
            is PortResult.Failure -> return result
            is PortResult.Success -> result.value
        }
        val queue = ArrayDeque<UiNode>()
        windows.mapNotNull { it.root }.forEach(queue::add)
        if (queue.isEmpty()) when (val root = tree.activeRoot()) {
            is ReadValue.Present -> queue.add(root.value)
            is ReadValue.Unreadable -> return PortResult.Failure(root.failure)
            ReadValue.Absent -> return unavailable("No visible Speaker control is available.")
        }
        val seen = mutableSetOf<UiNode>()
        while (queue.isNotEmpty() && seen.size < 512) {
            val node = queue.removeFirst()
            if (!seen.add(node)) continue
            val properties = when (val result = node.read()) {
                is PortResult.Failure -> return result
                is PortResult.Success -> result.value
            }
            val label = properties.text ?: properties.contentDescription
            val visible = properties.bounds.right > properties.bounds.left && properties.bounds.bottom > properties.bounds.top
            if (visible && properties.enabled && properties.clickable && label?.trim()?.equals("Speaker", true) == true) {
                return node.perform(NodeAction.Simple(NodeActionKind.CLICK))
            }
            when (val children = node.children()) {
                is PortResult.Failure -> return children
                is PortResult.Success -> children.value.forEach(queue::add)
            }
        }
        return unavailable(if (queue.isEmpty()) "No visible Speaker control is available." else "Speaker search exceeded its node budget.")
    }

    private fun unavailable(message: String) = PortResult.Failure(PortFailure(FailureKind.UNAVAILABLE, IllegalStateException(message)))
}
