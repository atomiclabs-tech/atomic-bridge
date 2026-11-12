package tech.atomiclabs.link.core.phone

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import tech.atomiclabs.link.core.command.BadRequestException
import tech.atomiclabs.link.core.command.BodyParams

class AttachmentSpecTest {
    private fun parse(json: String) = AttachmentSpec.parse(BodyParams(Json.parseToJsonElement(json).jsonObject))

    @Test fun everySingleTokenAndMetadataAliasAndMarkerProducesTheSameSpec() {
        val expected = listOf(AttachmentSpec("fixture-token", "image/png", "photo.png"))
        for (key in listOf("media", "media_token", "token")) {
            for (type in listOf("content_type", "mime_type")) {
                for (file in listOf("file_name", "filename")) {
                    for (value in listOf("fixture-token", "MEDIA:hermes-relay://fixture-token")) {
                        assertEquals(expected, parse("""{"$key":"$value","$type":"image/png","$file":"photo.png"}"""))
                        assertEquals(expected, parse("""{"attachments":[{"$key":"$value","$type":"image/png","$file":"photo.png"}]}"""))
                    }
                }
            }
        }
    }

    @Test fun arrayTokensAndAttachmentObjectsKeepOrderAndOptionalMetadata() {
        assertEquals(listOf(AttachmentSpec("one", "image/png", "one.png"),
            AttachmentSpec("two", "image/png", "one.png"), AttachmentSpec("three", null, null)),
            parse("""{"media_tokens":["one","MEDIA:hermes-relay://two"],"mime_type":" image/png ",
                "filename":" one.png ","attachments":[{"media":" three "}]}"""))
    }

    @Test fun canonicalKeysWinAndInvalidCanonicalValuesNeverFallThroughToAnAlias() {
        assertEquals(listOf(AttachmentSpec("one", "image/png", "one.png")),
            parse("""{"media":"one","token":"two","content_type":"image/png","mime_type":"text/plain",
                "file_name":"one.png","filename":"two.txt"}"""))
        assertThrows(BadRequestException::class.java) { parse("""{"media":null,"token":"one"}""") }
    }

    @Test fun malformedOrMissingAttachmentsAreBadRequestsWithoutIncludingTheirValues() {
        for (input in listOf("{}", """{"attachments":[]}""", """{"attachments":"private-value"}""",
            """{"attachments":[2]}""", """{"attachments":[{}]}""", """{"media_tokens":[null]}""",
            """{"media_tokens":[9]}""", """{"token":""}""", """{"token":"MEDIA:hermes-relay://"}""",
            """{"token":".."}""", """{"media":"one","mime_type":""}""", """{"media":"one","filename":9}""")) {
            val error = assertThrows(input, BadRequestException::class.java) { parse(input) }
            assertFalse(error.message!!.contains("private-value"))
        }
    }
}
