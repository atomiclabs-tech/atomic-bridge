package tech.atomiclabs.link.core.phone

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import tech.atomiclabs.link.core.command.*
import tech.atomiclabs.link.core.port.*

class IntentSpecTest {
    @Test fun parsesAllFieldsAndKeepsScalarTypesAndRelativeComponent() {
        val spec = IntentSpec.parse(BodyParams(Json.parseToJsonElement("""{"action":"VIEW","data":"sample:item","package":"p","component":"p/.Screen","category":"DEFAULT","extras":{"text":"private-extra","integer":7,"decimal":1.5,"flag":true}}""").jsonObject))
        assertEquals("VIEW", spec.action)
        assertEquals("sample:item", spec.data)
        assertEquals("p", spec.packageName)
        assertEquals(IntentComponent("p", "p.Screen"), spec.component)
        assertEquals("DEFAULT", spec.category)
        assertEquals(mapOf("text" to IntentExtra.Text("private-extra"), "integer" to IntentExtra.Integer(7),
            "decimal" to IntentExtra.Decimal(1.5), "flag" to IntentExtra.Flag(true)), spec.extras)
    }

    @Test fun minimalAndBroadcastFieldsStayOptional() {
        val body = BodyParams(buildJsonObject { put("action", "SEND") })
        val spec = IntentSpec.parse(body, activity = false)
        assertNull(spec.data); assertNull(spec.component); assertNull(spec.packageName); assertNull(spec.category)
        assertTrue(spec.extras.isEmpty())
    }

    @Test fun invalidPayloadsHaveOnlyPublicParameterNamesInErrors() {
        for (json in listOf(
            """{}""", """{"action":" "}""", """{"action":"A","component":"private-invalid"}""",
            """{"action":"A","component":"/Screen"}""", """{"action":"A","component":"p/a/b"}""",
            """{"action":"A","extras":{"private-name":null}}""",
            """{"action":"A","extras":{"private-name":[]}}""",
            """{"action":"A","extras":{"private-name":{}}}""",
            """{"action":"A","extras":{"private-name":9223372036854775808}}""",
            """{"action":"A","extras":{"private-name":1e999}}""",
        )) {
            val failure = assertThrows(BadRequestException::class.java) {
                IntentSpec.parse(BodyParams(Json.parseToJsonElement(json).jsonObject))
            }
            assertFalse(failure.message!!.contains("private-"))
        }
    }
}
