package tech.atomiclabs.link.core.input

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import tech.atomiclabs.link.core.port.*

class SwipeGeometryTest {
    @Test fun everyDirectionAndDistanceUsesTheSpecifiedScreenCenteredCoordinates() = runTest {
        val f = InputFixture(testScheduler)
        for ((distance, offsets) in listOf("short" to (108 to 240), "medium" to (216 to 480), "long" to (378 to 840))) {
            for ((direction, delta) in listOf("up" to (0 to -offsets.second), "down" to (0 to offsets.second),
                "left" to (-offsets.first to 0), "right" to (offsets.first to 0))) {
                val stroke = f.geometry.swipe(params("direction" to direction, "distance" to distance))
                assertEquals(listOf(Point((540 - delta.first).toFloat(), (1200 - delta.second).toFloat()),
                    Point((540 + delta.first).toFloat(), (1200 + delta.second).toFloat())), stroke.points)
                assertEquals(300, stroke.durationMs)
            }
        }
    }

    @Test fun coordinateSwipeAndDurationAliasDragEchoTheirResolvedInputs() = runTest {
        val f = InputFixture(testScheduler)
        for (path in listOf("/swipe", "/drag")) {
            val result = f.send(path, "start_x" to 1, "start_y" to 2, "end_x" to 8, "end_y" to 9, "duration" to 700)
            assertEquals(1, result["start_x"]!!.jsonPrimitive.int)
            assertEquals(9, result["end_y"]!!.jsonPrimitive.int)
            assertEquals(700, result["duration_ms"]!!.jsonPrimitive.long)
        }
        assertEquals("bad_request", f.send("/swipe", "direction" to "diagonal")["code"]!!.jsonPrimitive.content)
        assertEquals("bad_request", f.send("/drag", "start_x" to 1, "start_y" to 2, "end_x" to 3, "end_y" to 4,
            "duration" to 99)["code"]!!.jsonPrimitive.content)
    }

    @Test fun unreadableMetricsAndRefusedSwipesReturnFailures() = runTest {
        val f = InputFixture(testScheduler)
        f.metrics = refused
        assertEquals("gesture_failed", f.send("/swipe", "direction" to "up")["code"]!!.jsonPrimitive.content)
        f.metrics = PortResult.Success(DisplayMetrics(1080, 2400, 3f)); f.gestures.result = refused
        assertEquals("gesture_failed", f.send("/swipe", "direction" to "down")["code"]!!.jsonPrimitive.content)
    }

    @Test fun dragHoldIsParsedAndEchoedIncludingTheAllowedEndpoints() = runTest {
        for (hold in listOf(0, 800, 3000)) {
            val f = InputFixture(testScheduler)
            val result = f.send("/drag", "start_x" to 1, "start_y" to 2, "end_x" to 8, "end_y" to 9,
                "hold_ms" to hold)
            assertEquals(JsonPrimitive(hold), result["hold_ms"])
            assertEquals(500, f.gestures.calls.single().single().durationMs)
        }
    }

    @Test fun invalidDragHoldsNameTheParameterAndNeverDispatch() = runTest {
        val f = InputFixture(testScheduler)
        for (hold in listOf(3001, -1, 1.5, "invalid", true)) {
            val result = f.send("/drag", "start_x" to 1, "start_y" to 2, "end_x" to 8, "end_y" to 9,
                "hold_ms" to hold)
            assertEquals("hold=$hold", JsonPrimitive("bad_request"), result["code"])
            assertEquals("Parameter 'hold_ms' is missing or invalid.", result["error"]!!.jsonPrimitive.content)
        }
        assertTrue(f.gestures.calls.isEmpty())
    }
}
