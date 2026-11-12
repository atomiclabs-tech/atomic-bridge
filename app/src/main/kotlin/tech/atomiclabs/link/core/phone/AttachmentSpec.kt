// Ported from upstream app/src/main/kotlin/com/hermesandroid/relay/network/relay/BridgeCommandHandler.kt at commit da782a8.
package tech.atomiclabs.link.core.phone

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import tech.atomiclabs.link.core.command.BadRequestException
import tech.atomiclabs.link.core.command.BodyParams

data class AttachmentSpec(val token: String, val contentType: String?, val fileName: String?) {
    companion object {
        fun parse(body: BodyParams): List<AttachmentSpec> = buildList {
            val contentType = optional(body, "content_type")
            val fileName = optional(body, "file_name")
            body.string("media")?.let { add(spec(it, contentType, fileName)) }
            body.array("media_tokens")?.forEach { value ->
                val token = (value as? JsonPrimitive)?.takeIf { it.isString }?.content
                    ?: throw BadRequestException("media_tokens")
                add(spec(token, contentType, fileName))
            }
            body.array("attachments")?.forEach { value ->
                val attachment = BodyParams(value as? JsonObject ?: throw BadRequestException("attachments"))
                add(spec(attachment.requiredString("media"), optional(attachment, "content_type"),
                    optional(attachment, "file_name")))
            }
            if (isEmpty()) throw BadRequestException("attachments")
        }

        private fun spec(media: String, contentType: String?, fileName: String?): AttachmentSpec {
            val token = media.trim().removePrefix("MEDIA:hermes-relay://")
            if (token.isBlank() || token == "." || token == "..") throw BadRequestException("media")
            return AttachmentSpec(token, contentType, fileName)
        }

        private fun optional(body: BodyParams, name: String): String? = body.string(name)?.trim()?.also {
            if (it.isEmpty()) throw BadRequestException(name)
        }
    }
}
