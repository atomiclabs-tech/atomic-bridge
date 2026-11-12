package tech.atomiclabs.link.core.audio

import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import tech.atomiclabs.link.core.command.*
import tech.atomiclabs.link.core.log.Logger
import tech.atomiclabs.link.core.port.*

class ProbeRoutes(private val probe: AudioProbe, private val permissions: PermissionState,
    private val logger: Logger) : RouteGroup {
    override val routes = listOf(Route("POST", "/call_audio_probe") { body ->
        val plan = ProbePlan.parse(body)
        when (val permission = withTimeout(5_000) { permissions.read() }) {
            is PortResult.Failure -> failed(permission.failure)
            is PortResult.Success -> if (!permission.value.microphone) {
                CommandResult.Failure(ErrorCode.PERMISSION_DENIED, "Microphone permission is required.",
                    buildJsonObject { put("permission", "android.permission.RECORD_AUDIO") })
            } else when (val result = probe.run(plan)) {
                is PortResult.Failure -> failed(result.failure)
                is PortResult.Success -> CommandResult.Success(json(result.value))
            }
        }
    })

    private fun failed(failure: PortFailure): CommandResult.Failure {
        logger.error("ProbeRoutes", "run", "path=/call_audio_probe state=failed", FailureDiagnostics.sanitized(failure.cause))
        val permission = failure.permission.takeIf { failure.kind == FailureKind.PERMISSION_DENIED }
        val denied = permission != null
        return CommandResult.Failure(if (denied) ErrorCode.PERMISSION_DENIED else ErrorCode.AUDIO_UNAVAILABLE,
            if (denied) "An audio permission is required." else "The audio probe failed.", buildJsonObject {
                permission?.let { put("permission", it) }
                put("cause", failure.cause.message ?: failure.cause.javaClass.simpleName)
            })
    }

    private fun json(report: AudioProbeReport) = buildJsonObject {
        put("call_state", report.callState.name.lowercase())
        put("truncated", report.truncated)
        putJsonArray("captures") { report.captures.forEach { entry -> add(buildJsonObject {
            put("source", entry.source.name); put("speaker", if (entry.speaker) "on" else "off")
            put("init", entry.init.name.lowercase()); entry.error?.let { put("error", it) }
            put("bytes", entry.bytes); put("rms", entry.rms); put("peak", entry.peak); put("silent", entry.silent)
            put("sample", entry.sample?.let(::JsonPrimitive) ?: JsonNull)
        }) } }
        putJsonArray("injection") { report.playback.forEach { entry -> add(buildJsonObject {
            put("stream", entry.stream.name); put("speaker", if (entry.speaker) "on" else "off")
            put("played", entry.played); entry.error?.let { put("error", it) }
        }) } }
        putJsonArray("speaker_attempts") { report.speakerAttempts.forEach { entry -> add(buildJsonObject {
            put("speaker", if (entry.speaker) "on" else "off"); put("via", entry.via); put("switched", entry.switched)
            entry.error?.let { put("error", it) }
        }) } }
        putJsonObject("foreground") {
            put("started", report.foreground.started); report.foreground.error?.let { put("error", it) }
        }
    }
}
