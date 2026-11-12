package tech.atomiclabs.link.core.files

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import tech.atomiclabs.link.core.command.ErrorCode
import tech.atomiclabs.link.core.port.*

@OptIn(ExperimentalCoroutinesApi::class)
class FilePushTest {
    @Test fun pushUsesPartVerifiesThenRenamesAndWaitsForScan() = runTest {
        val f = FilesFixture()
        f.files.beforeRename = {
            assertTrue(f.files.writer.closed)
            assertArrayEquals(f.media.bytes, f.files.writer.bytes)
            assertFalse(f.files.calls.any { it.first == "scan" })
        }
        val response = f.call("/files/push", f.pushBody)
        assertEquals(JsonPrimitive(true), response.result["success"])
        assertEquals(JsonPrimitive("/shared/Download/target.jpg"), response.result["path"])
        assertEquals(JsonPrimitive(3), response.result["size"])
        assertEquals(JsonPrimitive(digest(f.media.bytes)), response.result["sha256"])
        assertEquals(JsonPrimitive(true), response.result["scanned"])
        assertEquals(listOf(f.endpoint to "fixture"), f.media.downloads)
        assertEquals("/shared/Download/target.jpg.part", f.files.calls.single { it.first == "create" }.second)
        assertEquals("/shared/Download/target.jpg.part->/shared/Download/target.jpg:false",
            f.files.calls.single { it.first == "rename" }.second)
        assertFalse(f.files.writer.discarded); assertEquals(3, f.files.writer.writes)
    }
    @Test fun checksumAndSizeMismatchesDiscardPartAndPreserveOldTarget() = runTest {
        for (badInfo in listOf(FileTransferInfo(3, "0".repeat(64), null), FileTransferInfo(2, digest(byteArrayOf(1, 2, 3)), null))) {
            val f = FilesFixture()
            val original = FileEntry("target.jpg", "/shared/Download/target.jpg", FileKind.FILE, 12, 19)
            f.files.entries[original.path] = original
            f.media.info = badInfo
            failure(f.call("/files/push", f.pushBody.dropLast(1) + ",\"overwrite\":true}"), ErrorCode.CHECKSUM_MISMATCH)
            assertTrue(f.files.writer.discarded)
            assertEquals(original, f.files.entries[original.path])
            assertFalse(f.files.calls.any { it.first in listOf("rename", "scan") })
        }
    }
    @Test fun existingTargetRefusesByDefaultAndExplicitOverwriteIsPassedToRename() = runTest {
        val f = FilesFixture()
        val path = "/shared/Download/target.jpg"
        f.files.entries[path] = FileEntry("target.jpg", path, FileKind.FILE, 2, 2)
        failure(f.call("/files/push", f.pushBody), ErrorCode.FILE_EXISTS)
        assertTrue(f.media.downloads.isEmpty()); assertFalse(f.files.calls.any { it.first == "create" })
        assertEquals(JsonPrimitive(true), f.call("/files/push", f.pushBody.dropLast(1) + ",\"overwrite\":true}").result["success"])
        assertTrue(f.files.calls.single { it.first == "rename" }.second.endsWith(":true"))
    }
    @Test fun namedDirectoryIsResolvedAndInvalidInputsNeverCreateOrFetch() = runTest {
        val f = FilesFixture()
        for (json in listOf(f.pushBody.replace("target.jpg", "../target.jpg"), f.pushBody.replace("target.jpg", "a\\\\b"),
            f.pushBody.replace("\"size\":3", "\"size\":-1"), f.pushBody.replace(digest(f.media.bytes), "invalid"),
            f.pushBody.replace("MEDIA:hermes-relay://fixture", "../token"))) {
            failure(f.call("/files/push", json), ErrorCode.BAD_REQUEST)
        }
        failure(f.call("/files/push", f.pushBody.replace("\"size\":3", "\"size\":${FilePaths.MAX_BYTES + 1}")), ErrorCode.FILE_TOO_LARGE)
        failure(f.call("/files/push", f.pushBody.dropLast(1) + ",\"dir\":\"../outside\"}"), ErrorCode.PATH_FORBIDDEN)
        assertTrue(f.media.downloads.isEmpty()); assertFalse(f.files.calls.any { it.first == "create" })
        val response = f.call("/files/push", f.pushBody.dropLast(1) + ",\"dir\":\"Pictures/new\"}")
        assertEquals(JsonPrimitive("/shared/Pictures/new/target.jpg"), response.result["path"])
    }
    @Test fun failedFetchAndRenameDiscardPartAndReturnRealFailure() = runTest {
        val f = FilesFixture()
        f.media.fetchFailure = PortFailure(FailureKind.REJECTED, java.io.IOException("private-token"), httpStatus = 403)
        val failed = f.call("/files/push", f.pushBody)
        failure(failed, ErrorCode.MEDIA_FETCH_FAILED)
        assertTrue(failed.result["error"]!!.jsonPrimitive.content.contains("403"))
        assertTrue(f.files.writer.discarded)
        f.media.fetchFailure = null
        f.files.failures["rename"] = FileResult.Failure(FileFailureKind.IO, PortFailure(FailureKind.IO, java.io.IOException("private-path")))
        failure(f.call("/files/push", f.pushBody), ErrorCode.INTERNAL_ERROR)
        assertTrue(f.files.writer.discarded); assertFalse(f.files.calls.any { it.first == "scan" })
        assertFalse(f.logger.entries.joinToString { it.cause?.stackTraceToString().orEmpty() }.contains("private-"))
    }
    @Test fun scanFalseFailureAndTimeoutReturnSuccessWithWarning() = runTest {
        for (mode in 0..2) {
            val f = FilesFixture()
            when (mode) {
                0 -> f.files.scanned = false
                1 -> f.files.failures["scan"] = FileResult.Failure(FileFailureKind.IO,
                    PortFailure(FailureKind.IO, java.io.IOException("private-name")))
                2 -> f.files.beforeScan = { awaitCancellation() }
            }
            val result = async { f.call("/files/push", f.pushBody) }
            advanceUntilIdle()
            assertEquals(JsonPrimitive(true), result.await().result["success"])
            assertEquals(JsonPrimitive(false), result.await().result["scanned"])
            assertEquals(1, f.logger.entries.count { it.level == "warn" })
            assertFalse(f.files.writer.discarded)
        }
        assertEquals(10_000, currentTime)
    }
    @Test fun duplicateRequestDuringAndAfterFetchReplaysAndNeverWritesTwice() = runTest {
        val f = FilesFixture()
        val release = CompletableDeferred<Unit>()
        f.media.beforeFetch = { release.await() }
        val first = async { f.call("/files/push", f.pushBody, id = "same-request") }
        runCurrent()
        val duplicate = async { f.call("/files/push", f.pushBody, id = "same-request") }
        runCurrent()
        release.complete(Unit)
        assertSame(first.await(), duplicate.await())
        assertSame(first.await(), f.call("/files/push", f.pushBody, id = "same-request"))
        assertEquals(1, f.files.calls.count { it.first == "create" })
        assertEquals(1, f.media.downloads.size); assertEquals(3, f.files.writer.writes)
        assertEquals(1, f.files.calls.count { it.first == "rename" })
        assertEquals(1, f.files.calls.count { it.first == "scan" })
    }
    @Test fun cancellationAndLegacyRouterDeadlineDiscardPartWithoutRenamingOrRepeating() = runTest {
        val f = FilesFixture()
        f.media.beforeFetch = { awaitCancellation() }
        val cancelled = async { f.call("/files/push", f.pushBody, id = "cancelled") }
        runCurrent(); cancelled.cancelAndJoin()
        assertTrue(f.files.writer.discarded)
        val timed = async { f.call("/files/push", f.pushBody, id = "timed") }
        advanceUntilIdle()
        failure(timed.await(), ErrorCode.TIMEOUT)
        assertEquals(25_000, currentTime)
        assertTrue(f.files.writer.discarded)
        assertSame(timed.await(), f.call("/files/push", f.pushBody, id = "timed"))
        assertEquals(2, f.media.downloads.size)
        assertFalse(f.files.calls.any { it.first == "rename" })
    }
}
