package tech.atomiclabs.link.core.audio

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import tech.atomiclabs.link.core.command.*
import tech.atomiclabs.link.core.fakes.*
import tech.atomiclabs.link.core.port.*
import tech.atomiclabs.link.core.protocol.BridgeCommand

class ProbePlanTest {
    private fun parse(json: String) = ProbePlan.parse(BodyParams(Json.parseToJsonElement(json).jsonObject))
    @Test fun clampsDurationExpandsBothAndKeepsApprovedBudgetsAndAllSources() {
        assertEquals(1_000L, parse("{\"duration_ms\":-1}").durationMs)
        assertEquals(10_000L, parse("{\"duration_ms\":999999999999}").durationMs)
        val defaults = parse("{}")
        assertEquals(4_000L, defaults.durationMs)
        assertEquals(110_000L, defaults.budgetMs); assertEquals(20_000L, defaults.routeBudgetMs)
        assertEquals(AudioCaptureSource.entries, defaults.sources)
        assertEquals(listOf(false, true), ProbePlan.speakers(parse("{\"speaker\":\"both\"}")))
        assertEquals(listOf(false), ProbePlan.speakers(parse("{\"speaker\":\"off\"}")))
        assertEquals(listOf(true), ProbePlan.speakers(parse("{\"speaker\":\"on\"}")))
        assertEquals(listOf(AudioCaptureSource.MIC), parse("{\"sources\":[\"MIC\",\"MIC\"]}").sources)
    }
    @Test fun invalidSourcesAndMalformedTypesBecomeBadRequestWithoutCallingProbe() = runTest {
        val fake = FakeAudioPorts()
        val routes = ProbeRoutes(fake, PermissionState { error("Permission read must not run.") }, FakeLogger())
        val router = CommandRouter(listOf(routes), FakeUiTree(), FakeDisplayGate(), FakeLogger())
        for (body in listOf("{\"sources\":[\"UNKNOWN\"]}", "{\"sources\":[4]}", "{\"speaker\":\"auto\"}",
            "{\"duration_ms\":null}", "{\"inject\":\"maybe\"}")) {
            val result = router.dispatch(BridgeCommand(body, "POST", "/call_audio_probe", body = Json.parseToJsonElement(body).jsonObject))
            assertEquals("bad_request", result.result["code"]!!.jsonPrimitive.content)
        }
        assertTrue(fake.plans.isEmpty())
    }
}
