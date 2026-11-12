package tech.atomiclabs.link.core.phone
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import tech.atomiclabs.link.core.command.*
import tech.atomiclabs.link.core.fakes.*
import tech.atomiclabs.link.core.port.*
import tech.atomiclabs.link.core.protocol.BridgeCommand

class LocationRoutesTest {
    private val logger = FakeLogger()
    private val fixes = Fixes()
    private fun router(test: TestScope) = CommandRouter(listOf(LocationRoutes(fixes, FakeClock(test.testScheduler, 1_000), logger)),
        FakeUiTree(), FakeDisplayGate(), logger)
    @Test fun bestAccuracyWinsAndFieldsStalenessAndAltitudeAreExact() = runTest {
        fixes.known = PortResult.Success(listOf(fix(30f, 990), fix(3f, 900), fix(0f, 999)))
        val result = router(this).dispatch(BridgeCommand("r", "GET", "/location")).result
        assertEquals(Json.parseToJsonElement("""{"latitude":1.0,"longitude":2.0,"accuracy":3.0,"provider":"gps","timestamp":900,"staleness_ms":100,"altitude":4.0,"success":true}"""), result)
        assertEquals(0, fixes.currents)
    }
    @Test fun noKnownFixRequestsExactlyOneCurrentFixForTenSecondsAndAllowsMissingAltitude() = runTest {
        fixes.current = ReadValue.Present(fix(1f, 2_000).copy(altitude = null))
        val response = router(this).dispatch(BridgeCommand("r", "GET", "/location")).result
        assertEquals(10_000, fixes.timeout)
        assertEquals(1, fixes.currents)
        assertEquals(0, response["staleness_ms"]!!.jsonPrimitive.long)
        assertFalse(response.containsKey("altitude"))
    }
    @Test fun absentTimedOutAndUnreadableFixesNeverSucceedOrHidePermissionNames() = runTest {
        for ((index, permission) in listOf("android.permission.ACCESS_FINE_LOCATION", "android.permission.ACCESS_BACKGROUND_LOCATION").withIndex()) {
            fixes.known = PortResult.Failure(PortFailure(FailureKind.PERMISSION_DENIED, SecurityException("private-location"), permission))
            val result = router(this).dispatch(BridgeCommand("p$index", "GET", "/location")).result
            assertEquals("permission_denied", result["code"]!!.jsonPrimitive.content)
            assertEquals(permission, result["permission"]!!.jsonPrimitive.content)
        }
        fixes.known = PortResult.Success(emptyList())
        assertEquals("location_unavailable", router(this).dispatch(BridgeCommand("absent", "GET", "/location")).result["code"]!!.jsonPrimitive.content)
        fixes.hang = true
        assertEquals("location_unavailable", router(this).dispatch(BridgeCommand("timeout", "GET", "/location")).result["code"]!!.jsonPrimitive.content)
        assertEquals(4, logger.entries.size)
        assertTrue(logger.entries.all { !it.cause!!.stackTraceToString().contains("private-") })
    }
    private fun fix(accuracy: Float, timestamp: Long) = LocationFix(1.0,2.0,accuracy,"gps",timestamp,4.0)
    private class Fixes : LocationSource {
        var known: PortResult<List<LocationFix>> = PortResult.Success(emptyList())
        var current: ReadValue<LocationFix> = ReadValue.Absent
        var currents = 0; var timeout = 0L; var hang = false
        override suspend fun lastKnown() = known
        override suspend fun current(timeoutMs: Long): ReadValue<LocationFix> {
            currents++; timeout = timeoutMs; if (hang) awaitCancellation(); return current
        }
    }
}
