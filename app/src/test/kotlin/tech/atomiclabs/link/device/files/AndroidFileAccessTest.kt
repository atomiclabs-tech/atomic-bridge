package tech.atomiclabs.link.device.files

import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Environment
import io.mockk.*
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import tech.atomiclabs.link.core.fakes.FakeLogger
import tech.atomiclabs.link.core.files.FilePaths
import tech.atomiclabs.link.core.files.FilePathFailure
import tech.atomiclabs.link.core.files.FileRoutes
import tech.atomiclabs.link.core.port.*

@OptIn(ExperimentalCoroutinesApi::class)
class AndroidFileAccessTest {
    @get:Rule val temporary = TemporaryFolder()
    private val context = mockk<Context>()
    private val logger = FakeLogger()
    private lateinit var shared: File
    private lateinit var cache: File
    @Before fun setup() {
        shared = temporary.newFolder("shared"); cache = temporary.newFolder("cache")
        every { context.cacheDir } returns cache
        mockkStatic(Environment::class)
        every { Environment.getExternalStorageDirectory() } returns shared
        every { Environment.getExternalStorageState() } returns Environment.MEDIA_MOUNTED
        every { Environment.isExternalStorageManager() } returns true
        mockkStatic(MediaScannerConnection::class)
    }
    @After fun teardown() { unmockkStatic(Environment::class, MediaScannerConnection::class) }
    private fun access(scheduler: TestCoroutineScheduler) = AndroidFileAccess(context, logger, StandardTestDispatcher(scheduler))
    private fun <T> value(result: FileResult<T>): T = (result as FileResult.Success).value
    private fun failed(result: FileResult<*>, kind: FileFailureKind): FileResult.Failure {
        assertTrue(result is FileResult.Failure)
        return (result as FileResult.Failure).also { assertEquals(kind, it.kind) }
    }
    @Test fun resolvesListsAndReadsSharedAndroidMediaAndApprovedCacheRoots() = runTest {
        val adapter = access(testScheduler)
        val directory = File(shared, "Android/media/example").apply { assertTrue(mkdirs()) }
        val source = File(directory, "fixture.txt").apply { writeText("abc") }
        val outbound = File(cache, "outbound").apply { assertTrue(mkdir()) }
        val probe = File(cache, "audio-probe").apply { assertTrue(mkdir()) }
        val share = File(outbound, "share.txt").apply { writeText("share") }
        val sample = File(probe, "sample.wav").apply { writeText("sample") }
        assertEquals(source.canonicalPath, value(adapter.resolve("Android/media/example/fixture.txt")))
        assertEquals(source.canonicalPath, value(adapter.resolve(source.path)))
        assertEquals(share.canonicalPath, value(adapter.resolve(share.path)))
        assertEquals(sample.canonicalPath, value(adapter.resolve(sample.path)))
        val roots = value(adapter.roots())
        assertEquals(listOf("Download", "DCIM", "Pictures", "Documents", "outbound"), roots.map { it.name })
        assertTrue(roots.all { it.kind == FileKind.DIRECTORY })
        assertEquals(listOf("fixture.txt"), value(adapter.list(directory.path)).map { it.name })
        val metadata = value(adapter.stat(source.path))
        assertEquals(3, metadata.size); assertEquals(source.lastModified(), metadata.modified)
        value(adapter.open(source.path)).use { handle ->
            val buffer = ByteArray(2)
            assertEquals(2, handle.read(buffer)); assertArrayEquals(byteArrayOf(97, 98), buffer)
            assertEquals(1, handle.read(buffer)); assertEquals(-1, handle.read(buffer))
        }
        assertTrue(logger.entries.isEmpty())
    }
    @Test fun lexicalTraversalSymlinkEscapesAndPrivateRootReplacementAreForbiddenOnEveryOperation() = runTest {
        val adapter = access(testScheduler)
        val outside = temporary.newFolder("outside")
        val secret = File(outside, "fixture").apply { writeText("untouched") }
        Files.createSymbolicLink(File(shared, "escape").toPath(), outside.toPath())
        for (path in listOf("../outside/fixture", secret.path, "escape/fixture", "escape/new", File(cache, "other").path)) {
            failed(adapter.resolve(path), FileFailureKind.FORBIDDEN)
        }
        failed(adapter.open("escape/fixture"), FileFailureKind.FORBIDDEN)
        failed(adapter.create("escape/new.part", 3), FileFailureKind.FORBIDDEN)
        failed(adapter.deleteTemp("escape/fixture.part"), FileFailureKind.FORBIDDEN)
        failed(adapter.rename("escape/fixture", "Download/x", true), FileFailureKind.FORBIDDEN)
        Files.createSymbolicLink(File(cache, "outbound").toPath(), outside.toPath())
        failed(adapter.resolve(File(cache, "outbound/fixture").path), FileFailureKind.FORBIDDEN)
        failed(adapter.roots(), FileFailureKind.FORBIDDEN)
        assertEquals("untouched", secret.readText())
        assertTrue(logger.entries.all { it.level == "error" && it.cause != null })
        assertFalse(logger.entries.joinToString { it.cause!!.stackTraceToString() }.contains(secret.name))
    }
    @Test fun missingOversizePermissionAndStorageFailuresRemainDistinct() = runTest {
        val adapter = access(testScheduler)
        failed(adapter.stat("missing"), FileFailureKind.NOT_FOUND)
        val large = File(shared, "large")
        RandomAccessFile(large, "rw").use { it.setLength(FilePaths.MAX_BYTES + 1) }
        failed(adapter.stat(large.path), FileFailureKind.TOO_LARGE)
        failed(adapter.open(large.path), FileFailureKind.TOO_LARGE)
        failed(adapter.create("large.part", FilePaths.MAX_BYTES + 1), FileFailureKind.TOO_LARGE)
        every { Environment.isExternalStorageManager() } returns false
        val denied = failed(adapter.roots(), FileFailureKind.PERMISSION_DENIED)
        assertEquals(FileRoutes.ALL_FILES, denied.failure.permission)
        every { Environment.isExternalStorageManager() } returns true
        every { Environment.getExternalStorageState() } returns Environment.MEDIA_UNMOUNTED
        failed(adapter.resolve("Download"), FileFailureKind.STORAGE_UNAVAILABLE)
    }
    @Test fun probeCacheIsReadableAndCannotBeChangedByFileTransferMutators() = runTest {
        val adapter = access(testScheduler)
        val probe = File(cache, "audio-probe").apply { assertTrue(mkdir()) }
        val sample = File(probe, "sample.wav").apply { writeText("sample") }
        assertEquals(6, value(adapter.stat(sample.path)).size)
        failed(adapter.create(File(probe, "new.part").path, 3), FileFailureKind.FORBIDDEN)
        failed(adapter.deleteTemp(File(probe, "sample.wav.part").path), FileFailureKind.FORBIDDEN)
        failed(adapter.rename(sample.path, "Download/sample.wav", true), FileFailureKind.FORBIDDEN)
        assertEquals("sample", sample.readText())
    }
    @Test fun createUsesPartFileAndAtomicRenamePreservesExistingUntilOverwriteIsExplicit() = runTest {
        val adapter = access(testScheduler)
        val target = File(shared, "Download/new/fixture")
        val part = File(target.path + ".part")
        val handle = value(adapter.create(part.path, 3))
        handle.write(byteArrayOf(1, 2, 3), 3); handle.close()
        assertFalse(target.exists()); assertArrayEquals(byteArrayOf(1, 2, 3), part.readBytes())
        target.writeText("old")
        failed(adapter.rename(part.path, target.path, false), FileFailureKind.EXISTS)
        assertEquals("old", target.readText()); assertTrue(part.exists())
        value(adapter.rename(part.path, target.path, true))
        assertArrayEquals(byteArrayOf(1, 2, 3), target.readBytes()); assertFalse(part.exists())
        val cleanup = value(adapter.create(part.path, 1))
        cleanup.close(); cleanup.discard(); cleanup.discard()
        assertFalse(part.exists()); assertTrue(target.exists())
        value(adapter.deleteTemp(part.path))
        failed(adapter.deleteTemp(target.path), FileFailureKind.FORBIDDEN)
    }
    @Test fun existingTempIsNeverTruncatedAndFilesystemErrorsAreLoggedAndSanitized() = runTest {
        val adapter = access(testScheduler)
        val part = File(shared, "fixture.part").apply { writeText("keep") }
        failed(adapter.create(part.path, 1), FileFailureKind.EXISTS)
        assertEquals("keep", part.readText())
        failed(adapter.create(part.path + "/child.part", 1), FileFailureKind.IO)
        failed(adapter.list(part.path), FileFailureKind.IO)
        every { context.cacheDir } throws SecurityException("private-path-data")
        failed(adapter.resolve("Download"), FileFailureKind.PERMISSION_DENIED)
        assertTrue(logger.entries.all { it.level == "error" && it.cause != null })
        assertFalse(logger.entries.joinToString { it.cause!!.stackTraceToString() }.contains("private-path-data"))
    }
    @Test fun cancellationDuringCreateDiscardsHandleBeforeDispatcherCanDeliverIt() = runTest {
        val adapter = access(testScheduler)
        lateinit var pending: Job
        every { context.cacheDir } answers { pending.cancel(); cache }
        pending = launch { adapter.create("Download/cancelled.part", 3) }
        runCurrent(); pending.join()
        assertTrue(pending.isCancelled)
        assertFalse(File(shared, "Download/cancelled.part").exists())
    }
    @Test fun discardedHandleRechecksParentConfinementBeforeDeletingPart() = runTest {
        val adapter = access(testScheduler)
        val handle = value(adapter.create("Download/fixture.part", 1))
        handle.write(byteArrayOf(1), 1); handle.close()
        val original = File(shared, "Download")
        Files.move(original.toPath(), File(shared, "saved").toPath())
        val outside = temporary.newFolder("outside")
        val protected = File(outside, "fixture.part").apply { writeText("untouched") }
        Files.createSymbolicLink(original.toPath(), outside.toPath())
        try { handle.discard(); fail("Deletion must recheck confinement") }
        catch (expected: FilePathFailure) { assertEquals(FileFailureKind.FORBIDDEN, expected.kind) }
        assertEquals("untouched", protected.readText())
        assertTrue(logger.entries.any { it.operation == "discard" && it.cause != null })
    }
    @Test fun scanReportsCallbackNullAndTimeoutAndIgnoresLateCallback() = runTest {
        val adapter = access(testScheduler)
        val target = File(shared, "fixture.jpg").apply { writeText("image") }
        val uri = mockk<Uri>()
        var listener: MediaScannerConnection.OnScanCompletedListener? = null
        every { MediaScannerConnection.scanFile(context, any(), null, any()) } answers {
            listener = arg(3)
            listener!!.onScanCompleted(target.path, uri)
        }
        assertEquals(true, value(adapter.scan(target.path, 10_000)))
        every { MediaScannerConnection.scanFile(context, any(), null, any()) } answers {
            arg<MediaScannerConnection.OnScanCompletedListener>(3).onScanCompleted(target.path, null)
        }
        assertEquals(false, value(adapter.scan(target.path, 10_000)))
        every { MediaScannerConnection.scanFile(context, any(), null, any()) } answers { listener = arg(3) }
        val response = async { adapter.scan(target.path, 10_000) }
        advanceUntilIdle()
        val timeout = failed(response.await(), FileFailureKind.IO)
        assertEquals(FailureKind.TIMED_OUT, timeout.failure.kind)
        assertEquals(10_000, currentTime)
        listener!!.onScanCompleted(target.path, uri)
        assertEquals(1, logger.entries.size)
    }
}
