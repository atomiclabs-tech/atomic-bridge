// Ported from upstream app/src/main/kotlin/com/hermesandroid/relay/accessibility/ActionExecutor.kt at commit da782a8.
package tech.atomiclabs.link.core.input

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import tech.atomiclabs.link.core.command.*
import tech.atomiclabs.link.core.port.Clock
import tech.atomiclabs.link.core.screen.*

class WaitCommand(private val windows: WindowOrder, private val walker: ScreenTreeWalker, private val clock: Clock) {
    suspend fun execute(body: BodyParams): CommandResult {
        if (!searching(body)) {
            val duration = (body.long("ms") ?: body.long("timeoutMs") ?: 0).coerceIn(0, 15_000)
            clock.sleep(duration)
            return CommandResult.Success(buildJsonObject { put("slept_ms", duration) })
        }
        val text = body.string("text")
        val className = body.string("className")
        val timeout = body.long("timeoutMs", 5_000).coerceIn(0, 20_000)
        val started = clock.monotonicMs()
        val progress = SearchProgress()
        val found = if (timeout == 0L) null else withTimeoutOrNull(timeout) {
            try { awaitNode(text, className, started + timeout, progress) }
            catch (cause: ScreenReadException) {
                // A platform adapter may return a typed failure while this search is being cancelled.
                currentCoroutineContext().ensureActive()
                throw cause
            }
        }
        val waited = clock.monotonicMs() - started
        return if (found != null) CommandResult.Success(buildJsonObject {
            put("found", true); put("waited_ms", waited); put("node", found.toJson()); put("visited", progress.visited)
        }) else CommandResult.Failure(ErrorCode.WAIT_TIMEOUT, "The requested node did not appear in time.",
            buildJsonObject {
                put("found", false); put("waited_ms", waited)
                put("truncated", progress.truncated); put("visited", progress.visited)
            })
    }

    private class SearchProgress {
        var visited = 0
        var truncated = false
        fun record(outcome: VisitOutcome) {
            visited = outcome.visited
            truncated = truncated || outcome.truncated
        }
    }

    private suspend fun awaitNode(text: String?, className: String?, deadlineMs: Long, progress: SearchProgress): ScreenNode? {
        while (clock.monotonicMs() < deadlineMs) {
            val snapshot = windows.snapshot()
            // A snapshot may consume the wait budget without starting another traversal.
            if (clock.monotonicMs() >= deadlineMs) return null
            var found: ScreenNode? = null
            val outcome = walker.visit(snapshot, MAX_SEARCH_VISITS, deadlineMs) { located ->
                val node = located.properties
                val textMatches = text == null || TextMatch.matchesNode(node, text)
                val classMatches = className == null || node.className == className || node.className?.substringAfterLast('.') == className
                if (textMatches && classMatches) found = ScreenNode(located.nodeId, node)
                found == null
            }
            progress.record(outcome)
            if (found != null) return found
            val remaining = deadlineMs - clock.monotonicMs()
            if (remaining <= 0) return null
            clock.sleep(minOf(250, remaining))
        }
        return null
    }

    companion object {
        fun searching(body: BodyParams): Boolean = body.element("ms") == null &&
            (body.element("text") != null || body.element("className") != null)
    }
}
