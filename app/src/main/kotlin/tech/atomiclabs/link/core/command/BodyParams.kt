package tech.atomiclabs.link.core.command

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Strict typed reads accept primitive/string scalars; supplied defaults apply only to absent keys. */
class BodyParams(private val body: JsonObject) {
    fun element(name: String, vararg aliases: String): JsonElement? {
        val names = listOf(name) + aliases + aliasGroups.firstOrNull { name in it }.orEmpty()
        return names.firstNotNullOfOrNull { body[it] }
    }

    fun requiredString(name: String): String = string(name) ?: invalid(name)
    fun string(name: String): String? = scalar(name) { if (it.isString) it.content else null }
    fun requiredBoolean(name: String): Boolean = boolean(name) ?: invalid(name)
    fun boolean(name: String): Boolean? = scalar(name) { it.content.toBooleanStrictOrNull() }
    fun boolean(name: String, default: Boolean): Boolean = boolean(name) ?: default
    fun requiredInt(name: String): Int = int(name) ?: invalid(name)
    fun int(name: String): Int? = scalar(name) { it.content.toIntOrNull() }
    fun int(name: String, default: Int): Int = int(name) ?: default
    fun requiredLong(name: String): Long = long(name) ?: invalid(name)
    fun long(name: String): Long? = scalar(name) { it.content.toLongOrNull() }
    fun long(name: String, default: Long): Long = long(name) ?: default
    fun objectValue(name: String): JsonObject? = structured(name) { it as? JsonObject }
    fun array(name: String): JsonArray? = structured(name) { it as? JsonArray }

    private fun <T> scalar(name: String, parse: (JsonPrimitive) -> T?): T? = structured(name) {
        (it as? JsonPrimitive)?.let(parse)
    }

    private fun <T> structured(name: String, parse: (JsonElement) -> T?): T? {
        val value = element(name) ?: return null
        return parse(value) ?: invalid(name)
    }

    private fun invalid(name: String): Nothing = throw BadRequestException(name)

    private companion object {
        val aliasGroups = listOf(
            listOf("nodeId", "node_id"), listOf("clearFirst", "clear_first"),
            listOf("timeoutMs", "timeout_ms"), listOf("className", "class_name"),
            listOf("duration", "duration_ms"), listOf("content_type", "mime_type"),
            listOf("file_name", "filename"), listOf("media", "media_token", "token"),
        )
    }
}

class BadRequestException(val parameter: String) :
    IllegalArgumentException("Parameter '$parameter' is missing or invalid.")
