package tech.atomiclabs.link.core.command

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class UnsupportedRoutesTest {
    @Test fun returnRouteIsExplicitlyUnsupportedAndRequiresNoDevicePreconditions() = runTest {
        val route = UnsupportedRoutes().routes.single()
        val body = BodyParams(JsonObject(emptyMap()))
        assertEquals("POST", route.method)
        assertEquals("/return_to_hermes", route.path)
        assertEquals(RouteNeeds(), route.needs(body))
        val result = route.handle(body) as CommandResult.Failure
        assertEquals(ErrorCode.UNSUPPORTED_COMMAND, result.code)
        assertEquals(JsonPrimitive(false), result.toJson()["success"])
        assertTrue(result.error.isNotBlank())
    }
}
