package tech.atomiclabs.link.core.command

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class BodyParamsTest {
    @Test fun readsPrimitiveAndStringBooleansIntsAndLongsIncludingFalseAndZero() {
        for (quoted in listOf(false, true)) {
            val params = BodyParams(buildJsonObject {
                put("flag", if (quoted) JsonPrimitive("false") else JsonPrimitive(false))
                put("count", if (quoted) JsonPrimitive("50") else JsonPrimitive(50))
                put("since", if (quoted) JsonPrimitive("9000000000") else JsonPrimitive(9_000_000_000L))
                put("zero", if (quoted) JsonPrimitive("0") else JsonPrimitive(0))
            })
            assertFalse(params.requiredBoolean("flag"))
            assertEquals(50, params.requiredInt("count"))
            assertEquals(9_000_000_000L, params.requiredLong("since"))
            assertEquals(0, params.int("zero", 10))
        }
    }

    @Test fun supportsEveryContractAliasInBothDirections() {
        val groups = listOf(
            listOf("nodeId", "node_id"), listOf("clearFirst", "clear_first"),
            listOf("timeoutMs", "timeout_ms"), listOf("className", "class_name"),
            listOf("duration", "duration_ms"), listOf("content_type", "mime_type"),
            listOf("file_name", "filename"), listOf("media", "media_token", "token"),
        )
        for (names in groups) for (provided in names) for (requested in names) {
            assertEquals("value", BodyParams(buildJsonObject { put(provided, "value") }).requiredString(requested))
        }
    }

    @Test fun explicitRequestedKeyWinsAndCallersCanNameAnAdditionalAlias() {
        val params = BodyParams(buildJsonObject { put("nodeId", "one"); put("node_id", "two"); put("custom", 3) })
        assertEquals("one", params.requiredString("nodeId"))
        assertEquals("two", params.requiredString("node_id"))
        assertEquals(JsonPrimitive(3), params.element("special", "custom"))
    }

    @Test fun missingAndInvalidValuesNameTheRequestedParameterWithoutEchoingTheValue() {
        for (body in listOf(JsonObject(emptyMap()), buildJsonObject { put("limit", "private-invalid-value") },
            buildJsonObject { put("limit", JsonNull) }, buildJsonObject { put("limit", JsonArray(emptyList())) })) {
            val failure = assertThrows(BadRequestException::class.java) { BodyParams(body).requiredInt("limit") }
            assertEquals("limit", failure.parameter)
            assertEquals("Parameter 'limit' is missing or invalid.", failure.message)
        }
    }

    @Test fun defaultsApplyOnlyToAbsentValuesAndOverflowIsAnError() {
        val absent = BodyParams(JsonObject(emptyMap()))
        assertEquals(50, absent.int("limit", 50))
        assertEquals(20L, absent.long("since", 20L))
        assertTrue(absent.boolean("flag", true))
        val params = BodyParams(buildJsonObject {
            put("limit", (Int.MAX_VALUE.toLong() + 1).toString()); put("since", "9223372036854775808"); put("flag", "False")
        })
        assertThrows(BadRequestException::class.java) { params.int("limit", 50) }
        assertThrows(BadRequestException::class.java) { params.long("since", 0) }
        assertThrows(BadRequestException::class.java) { params.boolean("flag", true) }
    }

    @Test fun stringsObjectsAndArraysAreTypedIncludingMediaTokensAndAttachmentShapes() {
        val params = BodyParams(buildJsonObject {
            put("text", "hello"); put("extras", buildJsonObject { put("enabled", true) })
            put("media_tokens", buildJsonArray { add("one"); add("two") })
            put("attachments", buildJsonArray { add(buildJsonObject { put("media", "one") }) })
        })
        assertEquals("hello", params.string("text"))
        assertEquals(JsonPrimitive(true), params.objectValue("extras")!!["enabled"])
        assertEquals(2, params.array("media_tokens")!!.size)
        assertEquals(1, params.array("attachments")!!.size)
        assertThrows(BadRequestException::class.java) { params.objectValue("text") }
        assertThrows(BadRequestException::class.java) { params.array("text") }
        assertThrows(BadRequestException::class.java) { BodyParams(buildJsonObject { put("text", 42) }).requiredString("text") }
    }
}
