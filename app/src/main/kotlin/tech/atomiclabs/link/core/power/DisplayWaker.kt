package tech.atomiclabs.link.core.power

import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import tech.atomiclabs.link.core.command.*
import tech.atomiclabs.link.core.log.Logger
import tech.atomiclabs.link.core.port.*

class DisplayWaker(
    private val power: PowerState,
    private val keyguard: KeyguardState,
    private val trampoline: Trampoline,
    private val clock: Clock,
    private val logger: Logger,
) : DisplayGate {
    override suspend fun prepare(): DisplayGateResult = try {
        prepareDisplay()
    } catch (cause: PreparationFailure) {
        logger.error("DisplayWaker", cause.operation, "state=preparation_failed",
            FailureDiagnostics.sanitized(cause.failure.cause))
        val permission = cause.failure.permission
        val code = if (cause.failure.kind == FailureKind.PERMISSION_DENIED && permission != null)
            ErrorCode.PERMISSION_DENIED else cause.code
        DisplayGateResult.Failed(CommandResult.Failure(code, "Display preparation could not complete.",
            buildJsonObject { if (code == ErrorCode.PERMISSION_DENIED) put("permission", permission) }))
    }

    private suspend fun prepareDisplay(): DisplayGateResult {
        var changed = false
        if (!interactive()) {
            checked(power.wakeWithBrightLock(10_000), ErrorCode.WAKE_FAILED, "wakeLock")
            changed = true
            if (!await(2_000) { interactive() }) {
                if (secureKeyguard()) return blocked()
                logger.warn("DisplayWaker", "trampolineFallback", "state=display_off")
                if (!wakeFromTrampoline()) return failed(ErrorCode.WAKE_FAILED, "The display did not turn on.")
            }
        }
        val guard = checked(keyguard.read(), ErrorCode.KEYGUARD_BLOCKED, "keyguardState")
        if (guard.showing) {
            if (guard.secure) return blocked()
            val deadline = clock.monotonicMs() + 3_000
            checked(trampoline.wakeAndDismiss(), ErrorCode.KEYGUARD_BLOCKED, "dismissTrampoline")
            if (!awaitUntil(deadline) { !checked(keyguard.read(), ErrorCode.KEYGUARD_BLOCKED, "keyguardState").showing }) {
                return blocked()
            }
            changed = true
        }
        if (changed) clock.sleep(300)
        return DisplayGateResult.Ready
    }

    private suspend fun secureKeyguard(): Boolean {
        val guard = checked(keyguard.read(), ErrorCode.KEYGUARD_BLOCKED, "keyguardState")
        return guard.showing && guard.secure
    }

    private suspend fun wakeFromTrampoline(): Boolean = coroutineScope {
        val deadline = clock.monotonicMs() + 2_000
        val wake = async { checked(trampoline.wakeAndDismiss(), ErrorCode.WAKE_FAILED, "wakeTrampoline") }
        try {
            val completed = withTimeoutOrNull(2_000) { wake.await(); true }
            if (completed == null) logger.warn("DisplayWaker", "wakeTrampolineDeadline", "state=callback_deadline")
            // Callback time and the remaining interactivity poll share one wake deadline.
            awaitUntil(deadline) { interactive() }
        } finally {
            // Cancel the job normally so the adapter does not mistake our deadline for its own timeout.
            wake.cancelAndJoin()
        }
    }

    private suspend fun interactive(): Boolean = checked(power.isInteractive(), ErrorCode.WAKE_FAILED, "interactive")

    private suspend fun await(durationMs: Long, condition: suspend () -> Boolean): Boolean =
        awaitUntil(clock.monotonicMs() + durationMs, condition)

    private suspend fun awaitUntil(deadline: Long, condition: suspend () -> Boolean): Boolean {
        while (true) {
            if (condition()) return true
            val remaining = deadline - clock.monotonicMs()
            if (remaining <= 0) return false
            clock.sleep(minOf(50, remaining))
        }
    }

    private fun blocked(): DisplayGateResult = failed(ErrorCode.KEYGUARD_BLOCKED, "The keyguard could not be dismissed.")

    private fun failed(code: ErrorCode, message: String): DisplayGateResult {
        logger.warn("DisplayWaker", "prepare", "state=${code.wireValue}")
        return DisplayGateResult.Failed(CommandResult.Failure(code, message))
    }

    private fun <T> checked(result: PortResult<T>, code: ErrorCode, operation: String): T = when (result) {
        is PortResult.Success -> result.value
        is PortResult.Failure -> throw PreparationFailure(result.failure, code, operation)
    }

    private class PreparationFailure(val failure: PortFailure, val code: ErrorCode, val operation: String) :
        IllegalStateException("Display preparation failed.", failure.cause)
}
