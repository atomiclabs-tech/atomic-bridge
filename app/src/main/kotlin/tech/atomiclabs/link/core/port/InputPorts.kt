// forge-allow: no-test declarations only
package tech.atomiclabs.link.core.port

/** Dispatches strokes once and awaits the bounded callback; cancellation/rejection is a failure. */
fun interface GestureInput { suspend fun dispatch(strokes: List<GestureStroke>): PortResult<Unit> }

/** Executes one accessibility global action and checks Android's boolean result. */
fun interface GlobalKeys { suspend fun perform(action: GlobalAction): PortResult<Unit> }

/** Adjusts the music stream without showing UI; failures retain the platform cause. */
fun interface VolumeControl { suspend fun adjust(direction: VolumeDirection): PortResult<Unit> }

data class Point(val x: Float, val y: Float)
data class GestureStroke(val points: List<Point>, val durationMs: Long, val startDelayMs: Long = 0, val holdMs: Long = 0)
enum class GlobalAction { BACK, HOME, RECENTS, NOTIFICATIONS, QUICK_SETTINGS, POWER_DIALOG, LOCK_SCREEN }
enum class VolumeDirection { UP, DOWN }
