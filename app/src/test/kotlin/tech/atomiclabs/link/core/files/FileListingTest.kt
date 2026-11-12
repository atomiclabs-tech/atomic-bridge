package tech.atomiclabs.link.core.files

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import tech.atomiclabs.link.core.command.ErrorCode
import tech.atomiclabs.link.core.port.*

class FileListingTest {
    @Test fun listingWithoutPathReturnsFiveLogicalDirectories() = runTest {
        val f = FilesFixture()
        val result = f.call().result
        assertEquals(JsonPrimitive(5), result["count"])
        assertEquals(JsonPrimitive(false), result["truncated"])
        val entries = result["entries"]!!.jsonArray
        assertEquals(listOf("Download", "DCIM", "Pictures", "Documents", "outbound"), entries.map { it.jsonObject["name"]!!.jsonPrimitive.content })
        assertTrue(entries.all { it.jsonObject["kind"] == JsonPrimitive("dir") })
    }
    @Test fun directoryEntriesAreSortedLimitedAndContainExactMetadata() = runTest {
        val f = FilesFixture()
        f.files.entries["/shared/Download/z"] = FileEntry("z", "/shared/Download/z", FileKind.DIRECTORY, 0, 11)
        f.files.entries["/shared/Download/a"] = FileEntry("a", "/shared/Download/a", FileKind.FILE, 8, 12)
        val result = f.call(json = """{"path":"Download","limit":"2"}""").result
        assertEquals(JsonPrimitive(2), result["count"]); assertEquals(JsonPrimitive(true), result["truncated"])
        val first = result["entries"]!!.jsonArray.first().jsonObject
        assertEquals(Json.parseToJsonElement("""{"name":"a","path":"/shared/Download/a","kind":"file","size":8,"modified":12}"""), first)
    }
    @Test fun defaultAndClampedLimitsAreHonoured() = runTest {
        val f = FilesFixture()
        repeat(2_010) { index ->
            val path = "/shared/Download/item${index.toString().padStart(4, '0')}"
            f.files.entries[path] = FileEntry(path.substringAfterLast('/'), path, FileKind.FILE, 0, 0)
        }
        for ((limit, count) in listOf("" to 200, ",\"limit\":0" to 1, ",\"limit\":9999" to 2_000)) {
            val result = f.call(json = """{"path":"Download"$limit}""").result
            assertEquals(JsonPrimitive(count), result["count"]); assertEquals(JsonPrimitive(true), result["truncated"])
        }
    }
    @Test fun fileIsNotADirectoryAndMalformedLimitDoesNotList() = runTest {
        val f = FilesFixture()
        failure(f.call(json = """{"path":"Download/source.jpg"}"""), ErrorCode.BAD_REQUEST)
        failure(f.call(json = """{"limit":"invalid"}"""), ErrorCode.BAD_REQUEST)
        assertFalse(f.files.calls.any { it.first == "list" })
    }
}
