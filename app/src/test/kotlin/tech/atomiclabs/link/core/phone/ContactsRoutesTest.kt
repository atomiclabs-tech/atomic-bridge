package tech.atomiclabs.link.core.phone
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import tech.atomiclabs.link.core.command.*
import tech.atomiclabs.link.core.fakes.*
import tech.atomiclabs.link.core.port.*
import tech.atomiclabs.link.core.protocol.BridgeCommand

class ContactsRoutesTest {
    @Test fun defaultAndBoundedLimitsAndExactNestedShapeRespectQuery() = runTest {
        val requested = mutableListOf<Pair<String,Int>>()
        val group = ContactsRoutes(ContactsSource { query,limit ->
            requested += query to limit
            PortResult.Success(listOf(Contact("7","private-name",listOf(ContactPhone("private-number",2,null)))))
        }, FakeLogger())
        val router = CommandRouter(listOf(group), FakeUiTree(), FakeDisplayGate(), FakeLogger())
        for ((index, body) in listOf("""{"query":"private-query"}""",
            """{"query":"private-query","limit":0}""", """{"query":"private-query","limit":101}""").withIndex()) {
            val result = router.dispatch(BridgeCommand("$index","POST","/search_contacts",body=Json.parseToJsonElement(body).jsonObject)).result
            assertEquals(Json.parseToJsonElement("""{"query":"private-query","count":1,"contacts":[{"id":"7","name":"private-name","phones":[{"number":"private-number","type":2,"label":null}]}],"success":true}"""),result)
        }
        assertEquals(listOf(20,1,100), requested.map { it.second })
        assertTrue(requested.all { it.first == "private-query" })
    }
    @Test fun actualDenialHasFullyQualifiedPermissionAndPrivateSafeLog() = runTest {
        val logger = FakeLogger()
        val group = ContactsRoutes(ContactsSource { _,_ ->
            PortResult.Failure(PortFailure(FailureKind.PERMISSION_DENIED,SecurityException("private-contact"),"android.permission.READ_CONTACTS"))
        }, logger)
        val router = CommandRouter(listOf(group),FakeUiTree(),FakeDisplayGate(),logger)
        val result = router.dispatch(BridgeCommand("p","POST","/search_contacts",body=buildJsonObject{put("query","private-query")})).result
        assertEquals("permission_denied",result["code"]!!.jsonPrimitive.content)
        assertEquals("android.permission.READ_CONTACTS",result["permission"]!!.jsonPrimitive.content)
        assertFalse(logger.entries.single().cause!!.stackTraceToString().contains("private-"))
        assertEquals("bad_request",router.dispatch(BridgeCommand("bad","POST","/search_contacts",
            body=buildJsonObject{put("query","")})).result["code"]!!.jsonPrimitive.content)
    }
}
