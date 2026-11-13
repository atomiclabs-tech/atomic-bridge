package tech.atomiclabs.link.core.unlock

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import tech.atomiclabs.link.core.events.EventBuffer
import tech.atomiclabs.link.core.command.*
import tech.atomiclabs.link.core.input.InputFailure
import tech.atomiclabs.link.core.input.NodeClick
import tech.atomiclabs.link.core.input.inputValue
import tech.atomiclabs.link.core.log.Logger
import tech.atomiclabs.link.core.port.*
import tech.atomiclabs.link.core.screen.*

/** Enters one sequence only after finding a complete pad; command results never describe keys. */
class UnlockRoutes(
    private val keyguard: KeyguardState,
    private val power: PowerState,
    private val gestures: GestureInput,
    private val metrics: ScreenMetrics,
    windows: WindowOrder,
    walker: ScreenTreeWalker,
    private val clock: Clock,
    private val logger: Logger,
    private val clicks: NodeClick,
    private val events: EventBuffer,
) : RouteGroup {
    private var entryUncertain = false
    private val pads = PinPad(windows, walker)
    override val routes = listOf(Route("POST", "/unlock", { RouteNeeds(accessibility = true, display = false, exclusive = true) }, ::unlock))

    private suspend fun unlock(body: BodyParams): CommandResult {
        val pin = body.requiredString("pin")
        if (pin.length !in 4..16 || pin.any { it !in '0'..'9' }) throw BadRequestException("pin")
        var stage = "keyguard"
        var attempted = false
        var capturing = false
        try {
            if (!showing()) return unlocked(already = true)
            events.beginSensitiveInput()
            capturing = true
            // A cancelled/refused partial entry cannot safely accept another sequence until the lock clears.
            if (entryUncertain) return failed("enter_pin", attempted)
            stage = "wake"
            power.wakeWithBrightLock(10_000).inputValue(ErrorCode.WAKE_FAILED, "The display did not turn on.")
            if (!poll(2_000) { power.isInteractive().inputValue(ErrorCode.WAKE_FAILED, "The display did not turn on.") }) {
                return failed(stage, attempted)
            }
            stage = "reveal_pin_pad"
            var pad = pads.find()
            if (pad == null) {
                val settled = settledKeyguard() ?: return failed("pin_pad_not_found", attempted)
                if (!settled.showing) return unlocked()
                pad = pads.find()
                if (pad == null) pad = revealedPad()
            }
            val keys = pad ?: return if (!showing()) unlocked() else failed("pin_pad_not_found", attempted)
            stage = "enter_pin"
            for ((index, digit) in pin.withIndex()) {
                if (index > 0) clock.sleep(120)
                clicks.clickExact(keys.digits.getValue(digit)) { attempted = true; entryUncertain = true }
            }
            stage = "confirm"
            keys.confirm?.let { clicks.clickExact(it) {} }
            stage = "pin_not_accepted"
            if (poll(5_000) { !showing() }) return unlocked()
            entryUncertain = false
            return failed(stage, attempted)
        } catch (cause: CancellationException) {
            entryUncertain = entryUncertain || attempted
            throw InterruptedCommandException(failed(stage, attempted, cause), cause)
        } catch (cause: InputFailure) {
            entryUncertain = entryUncertain || attempted
            return failed(stage, attempted, cause)
        } catch (cause: ScreenReadException) {
            entryUncertain = entryUncertain || attempted
            return failed(stage, attempted, cause)
        } finally {
            if (capturing) events.endSensitiveInput()
        }
    }

    private suspend fun showing(): Boolean = keyguard.read()
        .inputValue(ErrorCode.KEYGUARD_BLOCKED, "The keyguard state could not be read.").showing

    private suspend fun settledKeyguard(): KeyguardSnapshot? {
        var latest: KeyguardSnapshot? = null
        var stable: KeyguardSnapshot? = null
        var stableSince = clock.monotonicMs()
        // One interactive sample cannot establish that the keyguard has finished its transition.
        val ready = poll(2_000) {
            val interactive = power.isInteractive().inputValue(ErrorCode.KEYGUARD_BLOCKED, "The phone was not unlocked.")
            latest = keyguard.read().inputValue(ErrorCode.KEYGUARD_BLOCKED, "The phone was not unlocked.")
            if (!interactive || latest != stable) {
                stable = if (interactive) latest else null
                stableSince = clock.monotonicMs()
            }
            interactive && clock.monotonicMs() - stableSince >= 500
        }
        return if (ready) latest else null
    }

    private suspend fun revealedPad(): PinPad.Keys? {
        var pad: PinPad.Keys? = null
        repeat(2) {
            reveal()
            if (poll(2_000) { !showing() || run { pad = pads.find(); pad != null } }) return pad
        }
        return null
    }

    private suspend fun reveal() {
        val size = metrics.read().inputValue(ErrorCode.KEYGUARD_BLOCKED, "The phone was not unlocked.")
        val x = size.width / 2f
        gestures.dispatch(listOf(GestureStroke(listOf(Point(x, size.height * 0.80f), Point(x, size.height * 0.25f)), 250)))
            .inputValue(ErrorCode.KEYGUARD_BLOCKED, "The phone was not unlocked.")
    }

    private suspend fun poll(budgetMs: Long, ready: suspend () -> Boolean): Boolean {
        val deadline = clock.monotonicMs() + budgetMs
        while (true) {
            if (ready()) return true
            val remaining = deadline - clock.monotonicMs()
            if (remaining <= 0) return false
            clock.sleep(minOf(250, remaining))
        }
    }

    private fun unlocked(already: Boolean = false): CommandResult.Success {
        entryUncertain = false
        return CommandResult.Success(buildJsonObject {
            put("unlocked", true)
            if (already) put("already_unlocked", true)
        })
    }

    private fun failed(stage: String, attempted: Boolean, cause: Throwable? = null): CommandResult.Failure {
        val code = if (stage == "wake") ErrorCode.WAKE_FAILED else ErrorCode.KEYGUARD_BLOCKED
        val context = "state=${code.wireValue} stage=$stage"
        if (cause == null) logger.warn("UnlockRoutes", "unlock", context)
        else logger.error("UnlockRoutes", "unlock", context, FailureDiagnostics.sanitized(cause))
        val message = when (stage) {
            "keyguard" -> "The keyguard state could not be read."
            "wake" -> "The display did not turn on."
            else -> "The phone was not unlocked."
        }
        return CommandResult.Failure(code, message, buildJsonObject {
            put("stage", stage); put("pin_attempt_used", attempted)
        })
    }
}
