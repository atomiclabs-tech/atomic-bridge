// Ported from upstream app/src/main/kotlin/com/hermesandroid/relay/accessibility/ActionExecutor.kt at commit da782a8.
package tech.atomiclabs.link.device.access

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlinx.coroutines.*
import tech.atomiclabs.link.core.command.FailureDiagnostics
import tech.atomiclabs.link.core.log.Logger
import tech.atomiclabs.link.core.port.*

class GestureDispatcher(
    private val hub: AccessibilityHub, private val logger: Logger,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
) : GestureInput {
    override suspend fun dispatch(strokes: List<GestureStroke>): PortResult<Unit> {
        val current = hub.currentService()
        if (current is PortResult.Failure) return current
        val service = (current as PortResult.Success).value
        return try {
            val timeout = strokes.maxOfOrNull { it.startDelayMs + it.holdMs + it.durationMs } ?: 0
            withTimeout(timeout + 5_000) { withContext(dispatcher) {
                val held = mutableListOf<HeldStroke>()
                val result = awaitGesture(service, build(strokes, held))
                if (result is PortResult.Failure || held.isEmpty()) result
                else awaitGesture(service, continuation(held))
            } }
        } catch (cause: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            failure(cause, FailureKind.TIMED_OUT)
        } catch (cause: CancellationException) {
            throw cause
        } catch (cause: Exception) {
            failure(cause, if (cause is SecurityException) FailureKind.PERMISSION_DENIED else FailureKind.PLATFORM)
        }
    }

    private suspend fun awaitGesture(service: AccessibilityService, gesture: GestureDescription): PortResult<Unit> =
        suspendCancellableCoroutine { continuation ->
            val delivered = AtomicBoolean()
            fun complete(result: () -> PortResult<Unit>) {
                if (delivered.compareAndSet(false, true) && continuation.isActive) continuation.resume(result())
                else logger.warn("GestureDispatcher", "callback", "state=late_callback")
            }
            val callback = object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) = complete { PortResult.Success(Unit) }
                override fun onCancelled(gestureDescription: GestureDescription?) = complete {
                    failure(IllegalStateException("The gesture was cancelled."), FailureKind.REJECTED)
                }
            }
            if (!service.dispatchGesture(gesture, callback, null)) complete {
                failure(IllegalStateException("The gesture was refused."), FailureKind.REJECTED)
            }
        }

    private data class HeldStroke(val stroke: GestureStroke, val initial: GestureDescription.StrokeDescription)

    private fun build(strokes: List<GestureStroke>, held: MutableList<HeldStroke>): GestureDescription {
        require(strokes.isNotEmpty()) { "A gesture requires strokes." }
        val builder = GestureDescription.Builder()
        for (stroke in strokes) {
            require(stroke.points.isNotEmpty() && stroke.durationMs > 0 && stroke.startDelayMs >= 0 && stroke.holdMs >= 0) {
                "The gesture stroke is invalid."
            }
            val initial = if (stroke.holdMs > 0) {
                GestureDescription.StrokeDescription(path(stroke, hold = true), stroke.startDelayMs, stroke.holdMs, true)
                    .also { held += HeldStroke(stroke, it) }
            } else GestureDescription.StrokeDescription(path(stroke), stroke.startDelayMs, stroke.durationMs)
            builder.addStroke(initial)
        }
        return builder.build()
    }

    private fun continuation(held: List<HeldStroke>): GestureDescription {
        val builder = GestureDescription.Builder()
        for ((stroke, initial) in held) {
            builder.addStroke(initial.continueStroke(path(stroke), 0, stroke.durationMs, false))
        }
        return builder.build()
    }

    private fun path(stroke: GestureStroke, hold: Boolean = false): Path = Path().apply {
        stroke.points.forEachIndexed { index, point ->
            require(point.x.isFinite() && point.y.isFinite() && point.x >= 0 && point.y >= 0) { "The gesture point is invalid." }
            if (index == 0) moveTo(point.x, point.y) else if (!hold) lineTo(point.x, point.y)
        }
        if (hold) {
            // Give the continuing stroke a nonzero path, ending at the exact movement start.
            val start = stroke.points.first()
            val inwardX = if (start.x > 0) maxOf(0f, start.x - 1f) else 1f
            lineTo(inwardX, start.y)
            lineTo(start.x, start.y)
        }
    }

    private fun failure(cause: Exception, kind: FailureKind): PortResult.Failure {
        logger.error("GestureDispatcher", "dispatch", "state=gesture_failed", FailureDiagnostics.sanitized(cause))
        return PortResult.Failure(PortFailure(kind, cause))
    }
}
