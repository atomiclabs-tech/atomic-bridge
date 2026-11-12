package tech.atomiclabs.link.core.audio

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import tech.atomiclabs.link.core.command.*
import tech.atomiclabs.link.core.fakes.*
import tech.atomiclabs.link.core.port.*
import tech.atomiclabs.link.core.protocol.BridgeCommand

class ProbeRoutesTest {
    private fun permission(microphone: Boolean) = PermissionSnapshot(false, false, false, false, false, false, false, false, false,
        microphone = microphone)
    @Test fun missingMicrophoneReturnsExactPermissionAndDoesNotRequireDisplayOrAccessibility() = runTest {
        val fake = FakeAudioPorts(); val gate = FakeDisplayGate()
        val group = ProbeRoutes(fake, PermissionState { PortResult.Success(permission(false)) }, FakeLogger())
        assertEquals(RouteNeeds(), group.routes.single().needs(BodyParams(JsonObject(emptyMap()))))
        val router = CommandRouter(listOf(group), FakeUiTree().apply { connection = PortResult.Success(false) }, gate, FakeLogger())
        val result = router.dispatch(BridgeCommand("permission", "POST", "/call_audio_probe")).result
        assertEquals("permission_denied", result["code"]!!.jsonPrimitive.content)
        assertEquals("android.permission.RECORD_AUDIO", result["permission"]!!.jsonPrimitive.content)
        assertTrue(fake.plans.isEmpty()); assertEquals(0, gate.calls)
    }

    @Test fun reportContainsEveryMeasurementFieldAndReplayDoesNotRunAgain() = runTest {
        val fake = FakeAudioPorts()
        fake.report = AudioProbeReport(CallState.RINGING,
            listOf(AudioCaptureMeasurement(AudioCaptureSource.VOICE_CALL, false, AudioInitialization.FAILED, 0, 0.0, 0.0, true,
                error = "platform constructor rejected")),
            listOf(AudioPlaybackMeasurement(AudioPlaybackStream.USAGE_MEDIA, true, false, "platform write rejected")),
            listOf(SpeakerAttempt(true, "communication_device", false, "device rejected")), MicrophoneForegroundOutcome(false, "start rejected"), true)
        val logger = FakeLogger()
        val router = CommandRouter(listOf(ProbeRoutes(fake, PermissionState { PortResult.Success(permission(true)) }, logger)),
            FakeUiTree(), FakeDisplayGate(), logger)
        val command = BridgeCommand("report", "POST", "/call_audio_probe")
        val response = router.dispatch(command)
        assertEquals(response, router.dispatch(command)); assertEquals(1, fake.plans.size)
        val json = response.result
        assertEquals("ringing", json["call_state"]!!.jsonPrimitive.content); assertTrue(json["truncated"]!!.jsonPrimitive.boolean)
        val capture = json["captures"]!!.jsonArray.single().jsonObject
        assertEquals(setOf("source", "speaker", "init", "error", "bytes", "rms", "peak", "silent", "sample"), capture.keys)
        assertEquals(JsonNull, capture["sample"]); assertEquals("failed", capture["init"]!!.jsonPrimitive.content)
        assertFalse(json["injection"]!!.jsonArray.single().jsonObject["played"]!!.jsonPrimitive.boolean)
        assertEquals("communication_device", json["speaker_attempts"]!!.jsonArray.single().jsonObject["via"]!!.jsonPrimitive.content)
        assertEquals("start rejected", json["foreground"]!!.jsonObject["error"]!!.jsonPrimitive.content)
    }

    @Test fun unavailableProbeAndPermissionSnapshotReadErrorsKeepTheRealCauseAndSanitizeLogs() = runTest {
        val fake = FakeAudioPorts()
        val logger = FakeLogger()
        val failure = PortFailure(FailureKind.IO, IllegalStateException("private failure payload"))
        fake.failure = failure
        val group = ProbeRoutes(fake, PermissionState { PortResult.Success(permission(true)) }, logger)
        val result = group.routes.single().handle(BodyParams(JsonObject(emptyMap()))).toJson()
        assertEquals("audio_unavailable", result["code"]!!.jsonPrimitive.content)
        assertEquals("private failure payload", result["cause"]!!.jsonPrimitive.content)
        val unreadable = ProbeRoutes(fake, PermissionState { PortResult.Failure(failure) }, logger)
        assertFalse(unreadable.routes.single().handle(BodyParams(JsonObject(emptyMap()))).toJson()["success"]!!.jsonPrimitive.boolean)
        assertTrue(logger.entries.all { !it.cause!!.stackTraceToString().contains("private failure payload") })
    }

    @Test fun permissionQueryDenialWithoutNameReturnsTheRealCauseWithoutInventedMetadata() = runTest {
        val fake = FakeAudioPorts(); val logger = FakeLogger()
        val cause = SecurityException("private permission query detail")
        val failure = PortFailure(FailureKind.PERMISSION_DENIED, cause)
        val group = ProbeRoutes(fake, PermissionState { PortResult.Failure(failure) }, logger)
        val result = group.routes.single().handle(BodyParams(JsonObject(emptyMap()))).toJson()
        assertFalse(result["success"]!!.jsonPrimitive.boolean)
        assertEquals("audio_unavailable", result["code"]!!.jsonPrimitive.content)
        assertEquals(cause.message, result["cause"]!!.jsonPrimitive.content)
        assertFalse(result.containsKey("permission")); assertTrue(fake.plans.isEmpty())
        val router = CommandRouter(listOf(group), FakeUiTree(), FakeDisplayGate(), logger)
        assertEquals(result, router.dispatch(BridgeCommand("query-failed", "POST", "/call_audio_probe")).result)
        assertTrue(logger.entries.all { it.level == "error" && !it.cause!!.stackTraceToString().contains(cause.message!!) })
    }

    @Test fun knownPermissionDenialKeepsItsNameAndRealCauseFromQueryOrProbe() = runTest {
        val failure = PortFailure(FailureKind.PERMISSION_DENIED, SecurityException("phone state denied"),
            "android.permission.READ_PHONE_STATE")
        for (queryFailed in listOf(true, false)) {
            val fake = FakeAudioPorts().apply { if (!queryFailed) this.failure = failure }
            val group = ProbeRoutes(fake, PermissionState {
                if (queryFailed) PortResult.Failure(failure) else PortResult.Success(permission(true))
            }, FakeLogger())
            val result = group.routes.single().handle(BodyParams(JsonObject(emptyMap()))).toJson()
            assertFalse(result["success"]!!.jsonPrimitive.boolean)
            assertEquals("permission_denied", result["code"]!!.jsonPrimitive.content)
            assertEquals(failure.permission, result["permission"]!!.jsonPrimitive.content)
            assertEquals(failure.cause.message, result["cause"]!!.jsonPrimitive.content)
            assertEquals(if (queryFailed) 0 else 1, fake.plans.size)
        }
    }
}
