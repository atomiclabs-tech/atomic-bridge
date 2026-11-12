// Adapted from upstream app/src/main/kotlin/com/hermesandroid/relay/network/relay/BridgeCommandHandler.kt and app/src/main/kotlin/com/hermesandroid/relay/accessibility/ActionExecutor.kt at da782a8.
package tech.atomiclabs.link.core.phone

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import tech.atomiclabs.link.core.command.*
import tech.atomiclabs.link.core.log.Logger
import tech.atomiclabs.link.core.port.*
import tech.atomiclabs.link.core.screen.ScreenReadException
import tech.atomiclabs.link.core.screen.WindowOrder

const val OPEN_APP_FOREGROUND_TIMEOUT_MS = 5_000L

class AppRoutes(
    private val apps: AppControl,
    private val logger: Logger,
    private val tree: UiTreeSource,
    private val windows: WindowOrder,
    private val clock: Clock,
    private val foregroundEvents: ForegroundEvents,
) : RouteGroup {
    override val routes = listOf(
        Route("GET", "/apps") { list() },
        Route("GET", "/get_apps") { list() },
        Route("POST", "/open_app", { RouteNeeds(display = true) }) { open(it) },
        Route("POST", "/send_intent", { RouteNeeds(display = true) }) { start(it) },
        Route("POST", "/broadcast") { broadcast(it) },
    )

    private suspend fun list(): CommandResult = when (val result = apps.launchableApps()) {
        is PortResult.Success -> CommandResult.Success(buildJsonObject {
            put("apps", buildJsonArray { result.value.forEach { app ->
                add(buildJsonObject { put("package", app.packageName); put("label", app.label) })
            } })
            put("count", result.value.size)
        })
        is PortResult.Failure -> failed("list", result.failure)
    }

    private suspend fun open(body: BodyParams): CommandResult {
        val pkg = body.requiredString("package")
        if (pkg.isBlank()) throw BadRequestException("package")
        val connection = tree.isConnected()
        val beforeRead = clock.monotonicMs()
        val initial = if (connection is PortResult.Success && connection.value) readInitialForeground()
            else PortResult.Success(null)
        val started = clock.monotonicMs()
        return when (val result = apps.launch(pkg)) {
            AppLaunchOutcome.Launched -> when (initial) {
                is PortResult.Success -> waitForForeground(pkg, initial.value, started, connection)
                is PortResult.Failure -> if (initial.failure.kind == FailureKind.TIMED_OUT) failure(
                    "foreground", ErrorCode.TIMEOUT, "The app did not reach the foreground within 5 seconds.",
                    initial.failure.cause, foregroundFields(pkg, started - beforeRead, false, "unknown is in front."))
                else failed("foreground", initial.failure)
            }
            AppLaunchOutcome.NotFound -> failure("launch", ErrorCode.APP_NOT_FOUND,
                "The app is not installed or has no launcher.", IllegalStateException("No launchable app."))
            is AppLaunchOutcome.Failed -> failed("launch", result.failure)
        }
    }

    private suspend fun readInitialForeground(): PortResult<String?> = try {
        PortResult.Success(withTimeout(OPEN_APP_FOREGROUND_TIMEOUT_MS) { windows.foregroundPackage() })
    } catch (cause: ScreenReadException) {
        PortResult.Failure(cause.failure)
    } catch (cause: TimeoutCancellationException) {
        PortResult.Failure(PortFailure(FailureKind.TIMED_OUT, cause))
    }

    private suspend fun waitForForeground(
        pkg: String, initial: String?, started: Long, connection: PortResult<Boolean>,
    ): CommandResult {
        val connected = when (connection) {
            is PortResult.Success -> connection.value
            is PortResult.Failure -> {
                logger.error("AppRoutes", "foregroundConnection", "package=$pkg state=foreground_unverified",
                    FailureDiagnostics.sanitized(connection.failure.cause))
                false
            }
        }
        if (!connected) {
            logger.warn("AppRoutes", "foreground", "package=$pkg state=foreground_unverified")
            return CommandResult.Success(foregroundFields(pkg, null, false))
        }
        var current = initial
        try {
            val remaining = OPEN_APP_FOREGROUND_TIMEOUT_MS - (clock.monotonicMs() - started)
            val verified = withTimeoutOrNull(remaining) {
                try {
                    awaitForeground(pkg, started) { windows.foregroundPackage().also { current = it } }
                } catch (cause: ScreenReadException) {
                    currentCoroutineContext().ensureActive()
                    throw cause
                }
            } == true
            if (verified) return CommandResult.Success(foregroundFields(pkg, clock.monotonicMs() - started, true))
            // Keep the last completed read when the deadline cancels a poll or its boundary read.
        } catch (cause: ScreenReadException) {
            return failed("foreground", cause.failure)
        }
        val waited = clock.monotonicMs() - started
        if (current != null && current != initial && current != pkg) {
            logger.warn("AppRoutes", "foreground", "state=foreground_unverified package=$pkg front=$current")
            return CommandResult.Success(foregroundFields(pkg, waited, false,
                "$current is in front; the launched app's task may be behind it."))
        }
        return failure("foreground", ErrorCode.TIMEOUT,
            "The app did not reach the foreground within 5 seconds.", IllegalStateException("Foreground wait expired."),
            foregroundFields(pkg, waited, false, "${current ?: "unknown"} is in front."))
    }

    private suspend fun awaitForeground(pkg: String, started: Long, read: suspend () -> String?): Boolean {
        while (true) {
            if (read() == pkg || foregroundEvents.lastWindowStateAt(pkg)?.let { it >= started } == true) return true
            val remaining = OPEN_APP_FOREGROUND_TIMEOUT_MS - (clock.monotonicMs() - started)
            if (remaining <= 0) return false
            clock.sleep(minOf(250, remaining))
        }
    }

    private fun foregroundFields(pkg: String, waitedMs: Long?, verified: Boolean, hint: String? = null) = buildJsonObject {
        put("package", pkg); put("waited_ms", waitedMs); put("foreground_verified", verified)
        if (hint != null) put("hint", hint)
    }

    private suspend fun start(body: BodyParams): CommandResult {
        val spec = IntentSpec.parse(body)
        return when (val result = apps.startActivity(spec)) {
            ActivityOutcome.Started -> CommandResult.Success(buildJsonObject {
                put("action", spec.action); put("package", spec.packageName.orEmpty())
                put("component", body.string("component").orEmpty()); put("data", spec.data.orEmpty())
            })
            ActivityOutcome.NoHandler -> failure("startActivity", ErrorCode.NO_HANDLER,
                "No activity can handle the intent.", IllegalStateException("No intent handler."))
            is ActivityOutcome.Failed -> failed("startActivity", result.failure)
        }
    }

    private suspend fun broadcast(body: BodyParams): CommandResult {
        val spec = IntentSpec.parse(body, activity = false)
        return when (val result = apps.sendBroadcast(spec)) {
            is PortResult.Success -> CommandResult.Success(buildJsonObject {
                put("action", spec.action); put("package", spec.packageName.orEmpty()); put("data", spec.data.orEmpty())
            })
            is PortResult.Failure -> failed("broadcast", result.failure)
        }
    }

    private fun failed(operation: String, problem: PortFailure): CommandResult = failure(operation, when (problem.kind) {
        FailureKind.PERMISSION_DENIED -> ErrorCode.PERMISSION_DENIED
        FailureKind.TIMED_OUT -> ErrorCode.TIMEOUT
        else -> ErrorCode.INTERNAL_ERROR
    }, "The app operation could not complete.", problem.cause)

    private fun failure(
        operation: String, code: ErrorCode, message: String, cause: Throwable,
        fields: JsonObject = JsonObject(emptyMap()),
    ): CommandResult.Failure {
        logger.error("AppRoutes", operation, "state=failed", FailureDiagnostics.sanitized(cause))
        return CommandResult.Failure(code, message, fields)
    }
}
