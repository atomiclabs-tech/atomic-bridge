package tech.atomiclabs.link.core.files

import java.security.MessageDigest
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import tech.atomiclabs.link.core.command.*
import tech.atomiclabs.link.core.fakes.*
import tech.atomiclabs.link.core.port.*
import tech.atomiclabs.link.core.protocol.*

internal fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
    .joinToString("") { "%02x".format(it.toInt() and 255) }

internal class RecordingFiles : FileAccess {
    val policy = FilePaths("/shared", "/cache/outbound", "/cache/audio-probe")
    val entries = mutableMapOf<String, FileEntry>()
    val canonical = mutableMapOf<String, String>()
    val calls = mutableListOf<Pair<String, String>>()
    val failures = mutableMapOf<String, FileResult.Failure>()
    var reader = FakeFileReadHandle(byteArrayOf(1, 2, 3), chunkSize = 1)
    var writer = FakeFileWriteHandle(3)
    var scanned = true
    var beforeScan: suspend () -> Unit = {}
    var beforeRename: suspend () -> Unit = {}
    init {
        policy.listedRoots().forEach { entries[it] = FileEntry(it.substringAfterLast('/'), it, FileKind.DIRECTORY, 0, 0) }
        entries["/shared/Download/source.jpg"] = FileEntry("source.jpg", "/shared/Download/source.jpg", FileKind.FILE, 3, 7)
    }
    private fun <T> result(operation: String, path: String, value: () -> T): FileResult<T> {
        calls += operation to path
        return failures[operation] ?: FileResult.Success(value())
    }
    override suspend fun roots() = result("roots", "") { policy.listedRoots().map { entries.getValue(it) } }
    override suspend fun resolve(path: String): FileResult<String> = try {
        result("resolve", path) { policy.resolve(path) { canonical[it] ?: it } }
    } catch (cause: FilePathFailure) { FileResult.Failure(cause.kind, PortFailure(FailureKind.REJECTED, cause)) }
    override suspend fun list(path: String) = result("list", path) {
        entries.values.filter { it.path.substringBeforeLast('/') == path }
    }
    override suspend fun stat(path: String): FileResult<FileEntry> {
        calls += "stat" to path
        return failures["stat"] ?: entries[path]?.let { FileResult.Success(it) }
            ?: FileResult.Failure(FileFailureKind.NOT_FOUND, PortFailure(FailureKind.IO, java.io.FileNotFoundException("missing")))
    }
    override suspend fun open(path: String) = result("open", path) { reader }
    override suspend fun create(path: String, expectedSize: Long) = result("create", path) {
        writer = FakeFileWriteHandle(expectedSize)
        writer
    }
    override suspend fun rename(source: String, target: String, overwrite: Boolean): FileResult<Unit> {
        beforeRename()
        return result("rename", "$source->$target:$overwrite") {
            entries[target] = FileEntry(target.substringAfterLast('/'), target, FileKind.FILE, writer.expectedSize, 0)
        }
    }
    override suspend fun deleteTemp(path: String) = result("deleteTemp", path) { writer.discard() }
    override suspend fun scan(path: String, timeoutMs: Long): FileResult<Boolean> {
        assertEquals(10_000L, timeoutMs)
        beforeScan()
        return result("scan", path) { scanned }
    }
}

internal class RecordingMedia : MediaTransfer {
    val uploads = mutableListOf<Triple<String, String, String>>()
    val downloads = mutableListOf<Pair<String, String>>()
    var bytes = byteArrayOf(1, 2, 3)
    var uploadFailure: PortFailure? = null
    var fetchFailure: PortFailure? = null
    var info: FileTransferInfo? = null
    var beforeFetch: suspend () -> Unit = {}
    var beforeUpload: suspend () -> Unit = {}
    override suspend fun upload(httpBase: String, image: EncodedImage): PortResult<UploadedMedia> = error("Not a file upload")
    override suspend fun fetch(httpBase: String, token: String): PortResult<FetchedMedia> = error("Not a streamed download")
    override suspend fun uploadFile(httpBase: String, source: FileReadHandle, contentType: String,
        name: String): PortResult<UploadedFile> = source.use {
        uploads += Triple(httpBase, contentType, name)
        beforeUpload()
        uploadFailure?.let { return PortResult.Failure(it) }
        val hash = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(2)
        var size = 0L
        while (true) {
            val count = source.read(buffer)
            if (count == -1) break
            hash.update(buffer, 0, count); size += count
        }
        PortResult.Success(UploadedFile("uploaded-fixture", info ?: FileTransferInfo(size,
            hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) }, contentType)))
    }
    override suspend fun fetchToFile(httpBase: String, token: String, target: FileWriteHandle,
        maxBytes: Long): PortResult<FileTransferInfo> {
        assertEquals(FilePaths.MAX_BYTES, maxBytes)
        downloads += httpBase to token
        var complete = false
        return try {
            beforeFetch()
            fetchFailure?.let { return PortResult.Failure(it) }
            bytes.forEach { target.write(byteArrayOf(it), 1) }
            target.close()
            complete = true
            PortResult.Success(info ?: FileTransferInfo(bytes.size.toLong(), digest(bytes), null))
        } finally { if (!complete) target.discard() }
    }
}

internal class FilesFixture {
    val files = RecordingFiles()
    val media = RecordingMedia()
    val logger = FakeLogger()
    val tree = FakeUiTree().apply { connection = PortResult.Success(false) }
    val display = FakeDisplayGate()
    var permission = PortResult.Success(PermissionSnapshot(false, false, false, false, false, false, false, false, false,
        allFiles = true)) as PortResult<PermissionSnapshot>
    var beforePermission: suspend () -> Unit = {}
    var endpoint = "https://relay.invalid"
    val group = FileRoutes(files, media, PermissionState { beforePermission(); permission }, logger, Dispatchers.Unconfined) { endpoint }
    val router = CommandRouter(listOf(group), tree, display, logger)
    private var sequence = 0
    val pushBody get() = """{"media":"MEDIA:hermes-relay://fixture","name":"target.jpg","size":3,"sha256":"${digest(media.bytes)}"}"""
    suspend fun call(path: String = "/files", json: String = "{}", method: String = if (path == "/files") "GET" else "POST",
        id: String = "00000000-0000-0000-0000-${(++sequence).toString().padStart(12, '0')}") =
        router.dispatch(BridgeCommand(id, method, path, body = Json.parseToJsonElement(json).jsonObject))
}

internal fun failure(response: BridgeResponse, code: ErrorCode) {
    assertEquals(200, response.status)
    assertEquals(JsonPrimitive(false), response.result["success"])
    assertEquals(JsonPrimitive(code.wireValue), response.result["code"])
    assertTrue(response.result["error"]!!.jsonPrimitive.content.isNotBlank())
}
