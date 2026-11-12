// Ported from upstream app/src/main/kotlin/com/hermesandroid/relay/accessibility/ActionExecutor.kt at commit da782a8.
package tech.atomiclabs.link.core.input

import kotlinx.serialization.json.*
import tech.atomiclabs.link.core.command.*
import tech.atomiclabs.link.core.port.*

class SwipeGeometry(private val metrics: ScreenMetrics) {
    suspend fun swipe(body: BodyParams): GestureStroke {
        val direction = body.string("direction") ?: return coordinates(body, 300, false)
        val fraction = when (body.string("distance") ?: "medium") {
            "short" -> .2; "medium" -> .4; "long" -> .7
            else -> throw BadRequestException("distance")
        }
        val display = metrics.read().inputValue(ErrorCode.GESTURE_FAILED, "The display size could not be read.")
        if (display.width <= 0 || display.height <= 0) inputFail(ErrorCode.GESTURE_FAILED, "The display size is invalid.")
        return inside(Bounds(0, 0, display.width, display.height), direction, fraction,
            positiveDuration(body.long("duration_ms", 300)))
    }

    fun drag(body: BodyParams): GestureStroke {
        val hold = body.long("hold_ms", 0)
        if (hold !in 0..3000) throw BadRequestException("hold_ms")
        return coordinates(body, 500, true).copy(holdMs = hold)
    }

    private fun coordinates(body: BodyParams, defaultDuration: Long, bounded: Boolean): GestureStroke {
        val values = listOf("start_x", "start_y", "end_x", "end_y").map { name ->
            body.requiredInt(name).also { if (it < 0) throw BadRequestException(name) }
        }
        val duration = body.long("duration_ms", defaultDuration)
        if (bounded && duration !in 100..3000) throw BadRequestException("duration_ms")
        return GestureStroke(listOf(Point(values[0].toFloat(), values[1].toFloat()),
            Point(values[2].toFloat(), values[3].toFloat())), positiveDuration(duration))
    }

    fun inside(bounds: Bounds, direction: String, fraction: Double = .5, duration: Long = 300): GestureStroke {
        val middle = center(bounds)
        val dx = ((bounds.right.toLong() - bounds.left) * fraction / 2).toInt().toFloat()
        val dy = ((bounds.bottom.toLong() - bounds.top) * fraction / 2).toInt().toFloat()
        val offset = when (direction.lowercase()) {
            "up" -> Point(0f, -dy); "down" -> Point(0f, dy)
            "left" -> Point(-dx, 0f); "right" -> Point(dx, 0f)
            else -> throw BadRequestException("direction")
        }
        return GestureStroke(listOf(Point(middle.x - offset.x, middle.y - offset.y),
            Point(middle.x + offset.x, middle.y + offset.y)), duration)
    }

    fun fields(stroke: GestureStroke): JsonObject = buildJsonObject {
        put("start_x", stroke.points.first().x.toInt()); put("start_y", stroke.points.first().y.toInt())
        put("end_x", stroke.points.last().x.toInt()); put("end_y", stroke.points.last().y.toInt())
        put("duration_ms", stroke.durationMs)
    }

    fun dragFields(stroke: GestureStroke): JsonObject = buildJsonObject {
        fields(stroke).forEach { (key, value) -> put(key, value) }
        put("hold_ms", stroke.holdMs)
    }

    companion object {
        fun center(bounds: Bounds): Point {
            if (bounds.right <= bounds.left || bounds.bottom <= bounds.top) {
                inputFail(ErrorCode.GESTURE_FAILED, "The target bounds are invalid.")
            }
            return Point((bounds.left.toLong() + (bounds.right.toLong() - bounds.left) / 2).toFloat(),
                (bounds.top.toLong() + (bounds.bottom.toLong() - bounds.top) / 2).toFloat())
        }
        fun positiveDuration(value: Long): Long = value.also { if (it <= 0) throw BadRequestException("duration_ms") }
    }
}
