package tech.atomiclabs.link.core.command

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class CommandResultTest {
    @Test fun successAndFailureHaveContractFieldsAndPreserveExtras() {
        val success = CommandResult.Success(buildJsonObject { put("count", 3) }).toJson()
        assertTrue(success["success"]!!.jsonPrimitive.boolean)
        assertEquals(3, success["count"]!!.jsonPrimitive.int)
        assertFalse(success.containsKey("error"))
        val failure = CommandResult.Failure(ErrorCode.PERMISSION_DENIED, "Permission is missing.",
            buildJsonObject { put("permission", "android.permission.SEND_SMS") }).toJson()
        assertFalse(failure["success"]!!.jsonPrimitive.boolean)
        assertEquals("permission_denied", failure["code"]!!.jsonPrimitive.content)
        assertEquals("Permission is missing.", failure["error"]!!.jsonPrimitive.content)
        assertEquals("android.permission.SEND_SMS", failure["permission"]!!.jsonPrimitive.content)
    }

    @Test fun resultFieldsCannotOverrideProtocolMeaning() {
        for (key in listOf("success", "error", "code")) {
            assertThrows(IllegalArgumentException::class.java) {
                CommandResult.Success(buildJsonObject { put(key, "bad") }).toJson()
            }
            assertThrows(IllegalArgumentException::class.java) {
                CommandResult.Failure(ErrorCode.INTERNAL_ERROR, "Failed.", buildJsonObject { put(key, "bad") }).toJson()
            }
        }
    }

    @Test fun failureNeedsANonemptySingleLineSentence() {
        for (message in listOf("", "two\nlines", "two\rlines")) {
            assertThrows(IllegalArgumentException::class.java) {
                CommandResult.Failure(ErrorCode.BAD_REQUEST, message).toJson()
            }
        }
    }

    @Test fun errorVocabularyExactlyMatchesTheApprovedContract() {
        val expected = """unknown_command unsupported_command bad_request accessibility_disabled keyguard_blocked
            wake_failed node_not_found no_focused_input gesture_failed unsupported_key wait_timeout
            screenshot_rate_limited secure_window screenshot_failed upload_failed media_fetch_failed app_not_found
            no_handler clipboard_unavailable permission_denied location_unavailable sms_failed sms_unconfirmed timeout
            internal_error file_not_found path_forbidden file_too_large file_exists checksum_mismatch storage_unavailable
            audio_unavailable""".split(Regex("\\s+")).filter { it.isNotEmpty() }.toSet()
        assertEquals(expected, ErrorCode.entries.map { it.wireValue }.toSet())
    }
}
