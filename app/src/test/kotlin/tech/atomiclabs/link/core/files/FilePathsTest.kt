package tech.atomiclabs.link.core.files

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import tech.atomiclabs.link.core.command.BadRequestException
import tech.atomiclabs.link.core.command.ErrorCode
import tech.atomiclabs.link.core.port.*

class FilePathsTest {
    private val paths = FilePaths("/shared", "/private/outbound", "/private/audio-probe")
    @Test fun relativeAndAbsoluteSharedAndOnlyApprovedPrivateRootsResolve() {
        for ((input, expected) in listOf("Download/a" to "/shared/Download/a", "//shared/./Download//a" to "/shared/Download/a", "/shared/Android/media/pkg/a" to
            "/shared/Android/media/pkg/a", "/private/outbound/a" to "/private/outbound/a",
            "/private/audio-probe/a.wav" to "/private/audio-probe/a.wav")) {
            assertEquals(expected, paths.resolve(input) { it })
        }
    }
    @Test fun traversalOutsidePathsSiblingPrefixesAndFakeRealpathEscapesAreForbidden() {
        for (path in listOf("../a", "Download/../a", "/shared/../shared/a", "/shared-other/a", "/private/a", "", "a\u0000")) {
            forbidden { paths.resolve(path) { it } }
        }
        forbidden { paths.resolve("Download/link/a") { if (it.endsWith("link/a")) "/outside/a" else it } }
        forbidden { paths.resolve("/private/outbound/a") { it.replace("/private/outbound", "/private/secrets") } }
    }
    @Test fun rootsAreTheFourNamedFoldersAndShareCacheOnly() {
        assertEquals(listOf("/shared/Download", "/shared/DCIM", "/shared/Pictures", "/shared/Documents", "/private/outbound"),
            paths.listedRoots())
    }
    @Test fun probeCacheApprovalAllowsReadsAndForbidsFileTransferWrites() {
        assertEquals("/private/audio-probe/sample.wav", paths.resolve("/private/audio-probe/sample.wav") { it })
        forbidden { paths.resolveWrite("/private/audio-probe/sample.wav") { it } }
        assertEquals("/private/outbound/a", paths.resolveWrite("/private/outbound/a") { it })
    }
    @Test fun onlyPlainFileNamesAreAccepted() {
        assertEquals("a photo.jpg", FilePaths.name("a photo.jpg"))
        for (name in listOf("", ".", "..", "folder/name", "folder\\name", "a\n", "a\u0000")) {
            try { FilePaths.name(name); fail("A plain name is required") }
            catch (expected: BadRequestException) { assertEquals("name", expected.parameter) }
        }
    }
    @Test fun missingTooLargeAndForbiddenRetainDistinctCommandCodes() = runTest {
        val f = FilesFixture()
        failure(f.call("/files/pull", """{"path":"Download/missing"}"""), ErrorCode.FILE_NOT_FOUND)
        val source = f.files.entries.getValue("/shared/Download/source.jpg")
        f.files.entries[source.path] = source.copy(size = FilePaths.MAX_BYTES + 1)
        failure(f.call("/files/pull", """{"path":"Download/source.jpg"}"""), ErrorCode.FILE_TOO_LARGE)
        failure(f.call("/files/pull", """{"path":"../outside"}"""), ErrorCode.PATH_FORBIDDEN)
        assertTrue(f.media.uploads.isEmpty())
    }
    private fun forbidden(block: () -> Unit) {
        try { block(); fail("The path must be confined") }
        catch (expected: FilePathFailure) { assertEquals(FileFailureKind.FORBIDDEN, expected.kind) }
    }
}
