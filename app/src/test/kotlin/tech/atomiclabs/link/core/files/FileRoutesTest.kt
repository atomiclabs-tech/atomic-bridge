package tech.atomiclabs.link.core.files

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import tech.atomiclabs.link.core.command.*
import tech.atomiclabs.link.core.port.*

@OptIn(ExperimentalCoroutinesApi::class)
class FileRoutesTest {
    @Test fun routesNeedNeitherAccessibilityNorDisplay() = runTest {
        val f = FilesFixture()
        assertEquals(listOf("GET" to "/files", "POST" to "/files/pull", "POST" to "/files/push"),
            f.group.routes.map { it.method to it.path })
        assertTrue(f.group.routes.all { it.needs(BodyParams(JsonObject(emptyMap()))) == RouteNeeds() })
        assertEquals(JsonPrimitive(true), f.call().result["success"])
        assertEquals(0, f.tree.connectionReads); assertEquals(0, f.display.calls)
    }
    @Test fun missingAllFilesPermissionIsExplicitForEveryRouteAndPerformsNoIo() = runTest {
        val f = FilesFixture()
        f.permission = PortResult.Success((f.permission as PortResult.Success).value.copy(allFiles = false))
        for (path in listOf("/files", "/files/pull", "/files/push")) {
            val result = f.call(path)
            failure(result, ErrorCode.PERMISSION_DENIED)
            assertEquals(JsonPrimitive(FileRoutes.ALL_FILES), result.result["permission"])
        }
        assertTrue(f.files.calls.isEmpty()); assertTrue(f.media.downloads.isEmpty())
    }
    @Test fun permissionReadFailureAndTimeoutAreVisibleWithoutSensitiveDiagnostics() = runTest {
        val f = FilesFixture()
        val cause = IllegalStateException("private-phone-name-and-token")
        f.permission = PortResult.Failure(PortFailure(FailureKind.IO, cause))
        failure(f.call(), ErrorCode.INTERNAL_ERROR)
        assertTrue(f.logger.entries.single().cause!!.stackTrace.contentEquals(cause.stackTrace))
        assertFalse(f.logger.entries.toString().contains("private-phone-name-and-token"))
        f.beforePermission = { awaitCancellation() }
        val response = async { f.call() }
        advanceUntilIdle()
        failure(response.await(), ErrorCode.TIMEOUT)
        assertEquals(3_000, currentTime)
        assertTrue(f.files.calls.isEmpty())
    }
    @Test fun allTypedFileFailuresMapToExistingVocabulary() = runTest {
        val f = FilesFixture()
        for ((kind, code) in listOf(FileFailureKind.NOT_FOUND to ErrorCode.FILE_NOT_FOUND,
            FileFailureKind.FORBIDDEN to ErrorCode.PATH_FORBIDDEN, FileFailureKind.TOO_LARGE to ErrorCode.FILE_TOO_LARGE,
            FileFailureKind.EXISTS to ErrorCode.FILE_EXISTS, FileFailureKind.STORAGE_UNAVAILABLE to ErrorCode.STORAGE_UNAVAILABLE,
            FileFailureKind.PERMISSION_DENIED to ErrorCode.PERMISSION_DENIED, FileFailureKind.IO to ErrorCode.INTERNAL_ERROR)) {
            f.files.failures["roots"] = FileResult.Failure(kind, PortFailure(FailureKind.IO, java.io.IOException("private-path")))
            failure(f.call(), code)
        }
        assertTrue(f.logger.entries.all { it.level == "error" && it.cause != null })
        assertFalse(f.logger.entries.joinToString { it.cause!!.stackTraceToString() }.contains("private-path"))
    }
}
