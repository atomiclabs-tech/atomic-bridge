// Adapted from upstream app/src/main/kotlin/com/hermesandroid/relay/accessibility/ActionExecutor.kt at da782a8.
package tech.atomiclabs.link.core.phone
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import tech.atomiclabs.link.core.command.*
import tech.atomiclabs.link.core.log.Logger
import tech.atomiclabs.link.core.port.*

class LocationRoutes(private val source: LocationSource, private val clock: Clock, private val logger: Logger) : RouteGroup {
    override val routes = listOf(Route("GET", "/location") {
        when (val known = source.lastKnown()) {
            is PortResult.Failure -> failure(known.failure)
            is PortResult.Success -> {
                val best = known.value.minWithOrNull(compareBy<LocationFix> {
                    if (it.accuracy > 0) it.accuracy else Float.MAX_VALUE
                }.thenByDescending { it.timestampEpochMs })
                if (best != null) result(best) else current()
            }
        }
    })
    private suspend fun current(): CommandResult = try {
        when (val fix = withTimeout(10_000) { source.current(10_000) }) {
            is ReadValue.Present -> result(fix.value)
            ReadValue.Absent -> failure(PortFailure(FailureKind.UNAVAILABLE, IllegalStateException("No current fix.")))
            is ReadValue.Unreadable -> failure(fix.failure)
        }
    } catch (cause: TimeoutCancellationException) {
        failure(PortFailure(FailureKind.TIMED_OUT, cause))
    }
    private fun result(fix: LocationFix) = CommandResult.Success(buildJsonObject {
        put("latitude", fix.latitude); put("longitude", fix.longitude); put("accuracy", fix.accuracy)
        put("provider", fix.provider); put("timestamp", fix.timestampEpochMs)
        put("staleness_ms", (clock.epochMs() - fix.timestampEpochMs).coerceAtLeast(0))
        fix.altitude?.let { put("altitude", it) }
    })
    private fun failure(problem: PortFailure): CommandResult {
        logger.error("LocationRoutes", "read", "state=failed", FailureDiagnostics.sanitized(problem.cause))
        val denied = problem.kind == FailureKind.PERMISSION_DENIED
        return CommandResult.Failure(if (denied) ErrorCode.PERMISSION_DENIED else ErrorCode.LOCATION_UNAVAILABLE,
            if (denied) "Location permission is required." else "A location fix is unavailable.",
            buildJsonObject { if (denied) put("permission", checkNotNull(problem.permission)) })
    }
}
