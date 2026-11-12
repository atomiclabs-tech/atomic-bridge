package tech.atomiclabs.link.core.command

import org.junit.Assert.*
import org.junit.Test

class ErrorCodeTest {
    @Test fun wireValuesAreUniqueSnakeCaseAndNewCodesAreAppended() {
        val values = ErrorCode.entries.map { it.wireValue }
        assertEquals(values.size, values.toSet().size)
        assertTrue(values.all { Regex("[a-z]+(?:_[a-z]+)*").matches(it) })
        assertEquals(listOf("file_not_found", "path_forbidden", "file_too_large", "file_exists", "checksum_mismatch",
            "storage_unavailable", "audio_unavailable"), values.takeLast(7))
    }
}
