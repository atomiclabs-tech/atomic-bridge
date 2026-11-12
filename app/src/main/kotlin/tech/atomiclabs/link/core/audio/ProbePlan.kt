package tech.atomiclabs.link.core.audio

import kotlinx.serialization.json.JsonPrimitive
import tech.atomiclabs.link.core.command.BadRequestException
import tech.atomiclabs.link.core.command.BodyParams
import tech.atomiclabs.link.core.port.AudioCaptureSource
import tech.atomiclabs.link.core.port.AudioProbePlan
import tech.atomiclabs.link.core.port.ProbeSpeaker

object ProbePlan {
    fun parse(body: BodyParams): AudioProbePlan {
        val speaker = when (body.string("speaker") ?: "both") {
            "off" -> ProbeSpeaker.OFF
            "on" -> ProbeSpeaker.ON
            "both" -> ProbeSpeaker.BOTH
            else -> throw BadRequestException("speaker")
        }
        val sources = body.array("sources")?.map { value ->
            val name = (value as? JsonPrimitive)?.takeIf { it.isString }?.content
            AudioCaptureSource.entries.firstOrNull { it.name == name } ?: throw BadRequestException("sources")
        }?.distinct() ?: AudioCaptureSource.entries
        return AudioProbePlan(
            durationMs = body.long("duration_ms", 4_000).coerceIn(1_000, 10_000),
            speaker = speaker,
            inject = body.boolean("inject", true),
            sources = sources,
        )
    }

    fun speakers(plan: AudioProbePlan): List<Boolean> = when (plan.speaker) {
        ProbeSpeaker.OFF -> listOf(false)
        ProbeSpeaker.ON -> listOf(true)
        ProbeSpeaker.BOTH -> listOf(false, true)
    }
}
