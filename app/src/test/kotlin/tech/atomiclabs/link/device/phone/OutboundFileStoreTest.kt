package tech.atomiclabs.link.device.phone

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import io.mockk.*
import java.io.File
import java.nio.file.Files
import javax.xml.parsers.DocumentBuilderFactory
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import tech.atomiclabs.link.core.fakes.FakeLogger
import tech.atomiclabs.link.core.port.*

class OutboundFileStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private val context = mockk<Context>()
    private val logger = FakeLogger()
    private val uri = mockk<Uri>()
    private val written = mutableListOf<File>()

    @Before fun setup() {
        every { context.cacheDir } returns temporary.root
        every { context.packageName } returns "tech.example.atomic"
        every { uri.scheme } returns "content"
        every { uri.authority } returns "tech.example.atomic.files"
        every { uri.toString() } returns "content://tech.example.atomic.files/outbound/prepared"
        mockkStatic(FileProvider::class)
        every { FileProvider.getUriForFile(context, "tech.example.atomic.files", any()) } answers {
            written += thirdArg<File>(); uri
        }
    }
    @After fun teardown() { unmockkStatic(FileProvider::class) }

    @Test fun writesExactBytesWithConfinedSanitizedNameAndRealProviderAuthority() = runTest {
        val store = OutboundFileStore(context, logger, StandardTestDispatcher(testScheduler))
        val result = store.write(byteArrayOf(1, 2, 3), "../../folder\\private name.png", "image/png") as PortResult.Success
        assertArrayEquals(byteArrayOf(1, 2, 3), written.single().readBytes())
        assertEquals(File(temporary.root, "outbound").canonicalFile, written.single().canonicalFile.parentFile)
        assertTrue(written.single().name.endsWith("-private_name.png"))
        assertEquals(OutboundAttachment(uri.toString(), "image/png", written.single().name), result.value)
        assertTrue(logger.entries.isEmpty())
    }

    @Test fun repeatedNamesNeverOverwriteAnEarlierComposeFile() = runTest {
        val store = OutboundFileStore(context, logger, StandardTestDispatcher(testScheduler))
        store.write(byteArrayOf(1), "photo.png", "image/png")
        store.write(byteArrayOf(2), "photo.png", "image/png")
        assertNotEquals(written[0], written[1])
        assertArrayEquals(byteArrayOf(1), written[0].readBytes())
        assertArrayEquals(byteArrayOf(2), written[1].readBytes())
    }

    @Test fun pruneDeletesOnlyFilesOlderThanCutoffAndKeepsBoundaryAndNewerFiles() = runTest {
        val root = File(temporary.root, "outbound").apply { assertTrue(mkdir()) }
        val old = File(root, "old").apply { writeText("old"); assertTrue(setLastModified(1_000)) }
        val boundary = File(root, "boundary").apply { writeText("keep"); assertTrue(setLastModified(2_000)) }
        val newer = File(root, "new").apply { writeText("keep"); assertTrue(setLastModified(3_000)) }
        val outside = File(temporary.root, "outside").apply { writeText("keep") }
        val store = OutboundFileStore(context, logger, StandardTestDispatcher(testScheduler))
        assertEquals(PortResult.Success(Unit), store.prune(2_000))
        assertFalse(old.exists())
        assertTrue(boundary.exists())
        assertTrue(newer.exists())
        assertTrue(outside.exists())
        assertTrue(written.isEmpty())
    }

    @Test fun directoryAndProviderFailuresAreLoudAndIncompleteFilesAreRemoved() = runTest {
        val root = File(temporary.root, "outbound").apply { writeText("not a directory") }
        val store = OutboundFileStore(context, logger, StandardTestDispatcher(testScheduler))
        assertTrue(store.prune(2_000) is PortResult.Failure)
        assertTrue(store.write(byteArrayOf(1), null, "image/png") is PortResult.Failure)
        assertTrue(root.delete())
        every { FileProvider.getUriForFile(context, any(), any()) } throws IllegalStateException("private-path-and-name")
        val result = store.write(byteArrayOf(1), null, "image/png") as PortResult.Failure
        assertEquals(FailureKind.IO, result.failure.kind)
        assertEquals(0, root.listFiles()!!.size)
        assertEquals(3, logger.entries.size)
        assertTrue(logger.entries.all { it.level == "error" && it.cause != null })
        assertFalse(logger.entries.joinToString { it.cause!!.stackTraceToString() }.contains("private-path-and-name"))
    }

    @Test fun symlinkedRootAndEntriesCannotEscapeOutboundOrDeleteOutsideFiles() = runTest {
        val outside = temporary.newFolder("outside")
        val protected = File(outside, "keep").apply { writeText("keep"); assertTrue(setLastModified(1_000)) }
        val root = File(temporary.root, "outbound")
        Files.createSymbolicLink(root.toPath(), outside.toPath())
        val store = OutboundFileStore(context, logger, StandardTestDispatcher(testScheduler))
        assertTrue(store.write(byteArrayOf(1), "photo.png", "image/png") is PortResult.Failure)
        assertTrue(root.delete())
        assertTrue(root.mkdir())
        Files.createSymbolicLink(File(root, "escape").toPath(), protected.toPath())
        assertTrue(store.prune(2_000) is PortResult.Failure)
        assertEquals("keep", protected.readText())
        assertTrue(written.isEmpty())
    }

    @Test fun invalidFilenameAndUriAreFailuresAndNeverReturnAnAttachment() = runTest {
        val store = OutboundFileStore(context, logger, StandardTestDispatcher(testScheduler))
        assertTrue(store.write(byteArrayOf(1), "..", "image/png") is PortResult.Failure)
        every { uri.authority } returns "wrong.provider"
        assertTrue(store.write(byteArrayOf(1), "photo.png", "image/png") is PortResult.Failure)
        assertEquals(0, File(temporary.root, "outbound").listFiles()!!.size)
    }

    @Test fun fileProviderResourceExposesOnlyOutboundCache() {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File("src/main/res/xml/file_paths.xml"))
        assertEquals("paths", document.documentElement.tagName)
        val children = document.documentElement.childNodes
        val elements = (0 until children.length).map { children.item(it) }.filter { it.nodeType == org.w3c.dom.Node.ELEMENT_NODE }
        assertEquals(1, elements.size)
        val path = elements.single() as org.w3c.dom.Element
        assertEquals("cache-path", path.tagName)
        assertEquals("outbound", path.getAttribute("name"))
        assertEquals("outbound/", path.getAttribute("path"))
    }
}
