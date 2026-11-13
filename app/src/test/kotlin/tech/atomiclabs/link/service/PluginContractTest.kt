package tech.atomiclabs.link.service

import android.view.accessibility.AccessibilityEvent
import java.security.MessageDigest
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import tech.atomiclabs.link.core.command.ErrorCode
import tech.atomiclabs.link.core.events.EventBuffer
import tech.atomiclabs.link.core.fakes.*
import tech.atomiclabs.link.core.port.*
import tech.atomiclabs.link.core.protocol.BridgeCommand
import tech.atomiclabs.link.device.access.EventFeed

@OptIn(ExperimentalCoroutinesApi::class)
class PluginContractTest {
    private data class Tool(val name: String, val method: String, val path: String, val body: String = "{}", val accessibility: Boolean = false)
    private val fileBytes = byteArrayOf(1, 2, 3)
    private fun checksum(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun unlockBody() = buildJsonObject {
        put("pin", buildString { repeat(6) { append(('0'.code + kotlin.random.Random.nextInt(10)).toChar()) } })
    }.toString()
    // Payloads read from installed plugin 1.13.1 tools/android_tool.py; host-only setup/macro are excluded.
    private val tools = listOf(
        Tool("android_ping", "GET", "/ping"),
        Tool("android_read_screen", "GET", "/screen", """{"include_bounds":"false"}""", true),
        Tool("android_find_nodes", "POST", "/find_nodes", """{"limit":20,"text":"send payment","class_name":"android.widget.EditText","clickable":true}""", true),
        Tool("android_tap", "POST", "/tap", """{"nodeId":"w11:0"}""", true),
        Tool("android_tap_text", "POST", "/tap_text", """{"text":"send payment","exact":true}""", true),
        Tool("android_long_press", "POST", "/long_press", """{"duration":500,"node_id":"w11:0"}""", true),
        Tool("android_type", "POST", "/type", """{"text":"private typed fixture","clearFirst":true}""", true),
        Tool("android_swipe", "POST", "/swipe", """{"direction":"up","distance":"medium"}""", true),
        Tool("android_drag", "POST", "/drag", """{"start_x":10,"start_y":20,"end_x":30,"end_y":40,"duration_ms":500,"hold_ms":0}""", true),
        Tool("android_open_app", "POST", "/open_app", """{"package":"fixture.app"}"""),
        Tool("android_press_key", "POST", "/press_key", """{"key":"back"}""", true),
        Tool("android_screenshot", "GET", "/screenshot", accessibility = true),
        Tool("android_scroll", "POST", "/scroll", """{"direction":"down","nodeId":"w11:0"}""", true),
        Tool("android_wait", "POST", "/wait", """{"timeoutMs":5000,"text":"send payment","className":"android.widget.EditText"}""", true),
        Tool("android_get_apps", "GET", "/apps"),
        Tool("android_current_app", "GET", "/current_app", accessibility = true),
        Tool("android_media", "POST", "/media", """{"action":"play"}"""),
        Tool("android_screen_hash", "GET", "/screen_hash", accessibility = true),
        Tool("android_diff_screen", "POST", "/diff_screen", """{"previous_hash":"previous"}""", true),
        Tool("android_describe_node", "POST", "/describe_node", """{"nodeId":"w11:0"}""", true),
        Tool("android_events", "GET", "/events", """{"limit":"50","since":"0"}""", true),
        Tool("android_event_stream", "POST", "/events/stream", """{"enabled":true}""", true),
        Tool("android_clipboard_read", "GET", "/clipboard"),
        Tool("android_clipboard_write", "POST", "/clipboard", """{"text":"private clipboard fixture"}"""),
        Tool("android_send_intent", "POST", "/send_intent", """{"action":"android.intent.action.VIEW","data":"fixture:page","package":"fixture.app","component":"fixture.app/.Main","extras":{"text":"private extra","count":2,"enabled":true},"category":"android.intent.category.DEFAULT"}"""),
        Tool("android_broadcast", "POST", "/broadcast", """{"action":"fixture.ACTION","data":"fixture:page","package":"fixture.app","extras":{"text":"private extra"}}"""),
        Tool("android_location", "GET", "/location"),
        Tool("android_search_contacts", "POST", "/search_contacts", """{"query":"Fixture","limit":20}"""),
        Tool("android_call", "POST", "/call", """{"number":"+15550000000"}"""),
        Tool("android_send_sms", "POST", "/send_sms", """{"to":"+15550000000","body":"send payment"}"""),
        Tool("android_return_to_hermes", "POST", "/return_to_hermes"),
        Tool("android_share_media", "POST", "/share_media", """{"attachments":[{"media":"MEDIA:hermes-relay://fixture-media","content_type":"image/png","file_name":"fixture.png"}],"text":"private share text","title":"Fixture","package":"fixture.share"}"""),
        Tool("android_send_mms", "POST", "/send_mms", """{"to":"+15550000000","body":"private mms body","attachments":[{"media":"MEDIA:hermes-relay://fixture-media","content_type":"image/png","file_name":"fixture.png"}],"package":"fixture.sms"}"""),
        Tool("android_list_files", "GET", "/files", """{"limit":200}"""),
        Tool("android_pull_file", "POST", "/files/pull", """{"path":"Download/fixture.bin"}"""),
        Tool("android_push_file", "POST", "/files/push", """{"media":"MEDIA:hermes-relay://fixture-media","name":"fixture.bin","dir":"Pictures","size":3,"sha256":"${checksum(fileBytes)}","overwrite":true}"""),
        Tool("android_call_audio_probe", "POST", "/call_audio_probe", """{"duration_ms":1000,"speaker":"off","inject":false,"sources":["MIC"]}"""),
        Tool("android_unlock", "POST", "/unlock", unlockBody(), true),
    )
    private val ownedScopes = mutableListOf<CoroutineScope>()
    @After fun cleanup() { ownedScopes.forEach { it.cancel() } }

    private inner class Fixture(scheduler: TestCoroutineScheduler) {
        val logger = FakeLogger()
        val tree = FakeUiTree()
        val root = FakeUiNode(UiNodeProperties(Bounds(0, 0, 1080, 2400), 11,
            packageName = "fixture.app", text = "send payment", className = "android.widget.EditText",
            clickable = true, editable = true, focused = true, scrollable = true,
            selectionStart = 0, selectionEnd = 0, supportedActions = setOf(NodeActionKind.SCROLL_FORWARD)))
        val calls = mutableListOf<String>()
        val sms = mutableListOf<Pair<String, String>>()
        val volume = mutableListOf<VolumeDirection>()
        val globals = mutableListOf<GlobalAction>()
        var callFailure: PortFailure? = null
        var beforeSms: suspend () -> Unit = {}
        var sequence = 0
        val clock = FakeClock(scheduler, 1_700_000_000_000)
        val events = EventBuffer(clock)
        val inputs: LinkRuntime.Inputs
        val runtime: LinkRuntime
        init {
            tree.root = ReadValue.Present(root)
            tree.focus = ReadValue.Present(root)
            tree.windowResult = PortResult.Success(listOf(UiWindow(11, WindowType.APPLICATION, 1, 1,
                true, true, root, root.properties.bounds)))
            inputs = runtimeInputs(clock).copy(tree = tree, events = events,
                calls = CallControl { calls += it; callFailure?.let { PortResult.Failure(it) } ?: PortResult.Success(Unit) },
                sms = SmsSender { to, body -> sms += to to body; beforeSms(); SmsOutcome.Sent(1) },
                volume = VolumeControl { volume += it; PortResult.Success(Unit) },
                globals = GlobalKeys { globals += it; PortResult.Success(Unit) },
                media = { object : MediaTransfer {
                    override suspend fun upload(httpBase: String, image: EncodedImage) = PortResult.Success(UploadedMedia("fixture-upload"))
                    override suspend fun fetch(httpBase: String, token: String) = PortResult.Success(FetchedMedia(byteArrayOf(1), "image/png"))
                    override suspend fun uploadFile(httpBase: String, source: FileReadHandle, contentType: String,
                        name: String): PortResult<UploadedFile> = source.use {
                        val digest = MessageDigest.getInstance("SHA-256")
                        val buffer = ByteArray(2)
                        var size = 0L
                        while (true) {
                            val count = source.read(buffer)
                            if (count == -1) break
                            digest.update(buffer, 0, count); size += count
                        }
                        PortResult.Success(UploadedFile("fixture-file", FileTransferInfo(size,
                            digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }, contentType)))
                    }
                    override suspend fun fetchToFile(httpBase: String, token: String, target: FileWriteHandle,
                        maxBytes: Long): PortResult<FileTransferInfo> {
                        target.write(fileBytes, fileBytes.size); target.close()
                        return PortResult.Success(FileTransferInfo(fileBytes.size.toLong(), checksum(fileBytes), null))
                    }
                } })
            val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(scheduler))
            ownedScopes += scope
            runtime = LinkRuntime(logger, inputs, scope, installFailureHandler = {})
        }
        suspend fun send(tool: Tool, id: String = "00000000-0000-0000-0000-${(++sequence).toString().padStart(12, '0')}") =
            runtime.router.dispatch(BridgeCommand(id, tool.method, tool.path,
                body = Json.parseToJsonElement(tool.body).jsonObject))
        suspend fun post(path: String, body: String = "{}", id: String = "post-${++sequence}") =
            send(Tool("fixture", "POST", path, body), id)
    }

    @Test fun all34LegacyAndFourNewPhoneBackedPluginCallsUseTheProductionComposedRouter() = runTest {
        val fixture = Fixture(testScheduler)
        assertEquals(38, tools.size)
        assertEquals(38, tools.map { it.name }.toSet().size)
        assertEquals(34, tools.count { it.path !in setOf("/files", "/files/pull", "/files/push", "/call_audio_probe") })
        assertFalse(tools.any { it.name in setOf("android_setup", "android_macro") })
        for (tool in tools) {
            val response = fixture.send(tool)
            assertEquals(tool.name, 200, response.status)
            assertTrue(tool.name, response.result is JsonObject)
            assertNotEquals(tool.name, JsonPrimitive("unknown_command"), response.result["code"])
            assertNotEquals(tool.name, JsonPrimitive(true), response.result["confirmation_required"])
            if (tool.path == "/return_to_hermes") assertEquals(JsonPrimitive("unsupported_command"), response.result["code"])
        }
        val unknown = fixture.post("/made_up_path")
        assertEquals(JsonPrimitive("unknown_command"), unknown.result["code"])
        assertEquals(200, unknown.status)
    }

    @Test fun disconnectedAccessibilityBlocksEveryMarkedCaseAndLeavesEveryOtherToolUsable() = runTest {
        val fixture = Fixture(testScheduler)
        fixture.tree.connection = PortResult.Success(false)
        for (tool in tools) {
            val result = fixture.send(tool).result
            when {
                tool.accessibility -> assertEquals(tool.name, JsonPrimitive("accessibility_disabled"), result["code"])
                tool.path == "/return_to_hermes" -> assertEquals(JsonPrimitive("unsupported_command"), result["code"])
                else -> assertEquals(tool.name, JsonPrimitive(true), result["success"])
            }
        }
    }

    @Test fun smsCallAndPaymentTapCompleteWithoutAnyConfirmationBlocklistToggleOrLease() = runTest {
        val fixture = Fixture(testScheduler)
        for (path in listOf("/send_sms", "/call", "/tap_text")) {
            val tool = tools.single { it.path == path }
            val first = fixture.send(tool, id = path)
            assertEquals(path, JsonPrimitive(true), first.result["success"])
            assertSame(first, fixture.send(tool, id = path))
        }
        assertEquals(listOf("+15550000000"), fixture.calls)
        assertEquals(listOf("+15550000000" to "send payment"), fixture.sms)
        assertEquals(1, fixture.root.actions.count { it == NodeAction.Simple(NodeActionKind.CLICK) })
        assertTrue(fixture.logger.entries.none { entry ->
            listOf("send payment", "+15550000000", "private").any { value ->
                (entry.context + entry.cause?.stackTraceToString()).contains(value)
            }
        })
    }

    @Test fun composedFileAndProbeRoutesReturnTheirRealPortOutcomesAndReplayWritesOnce() = runTest {
        val fixture = Fixture(testScheduler)
        val files = fixture.inputs.files as FakeFileAccess
        val list = fixture.send(tools.single { it.path == "/files" }).result
        assertEquals(JsonPrimitive(5), list["count"])
        val pull = fixture.send(tools.single { it.path == "/files/pull" }).result
        assertEquals(JsonPrimitive("MEDIA:hermes-relay://fixture-file"), pull["media"])
        assertEquals(JsonPrimitive(checksum(fileBytes)), pull["sha256"])
        assertTrue((files.reader as FakeFileReadHandle).closed)
        val pushTool = tools.single { it.path == "/files/push" }
        val pushed = fixture.send(pushTool, id = "push-once")
        assertSame(pushed, fixture.send(pushTool, id = "push-once"))
        assertEquals(JsonPrimitive(true), pushed.result["scanned"])
        assertArrayEquals(fileBytes, (files.writer as FakeFileWriteHandle).bytes)
        assertEquals(1, files.calls.count { it == "create" })
        assertEquals(1, files.calls.count { it == "rename" })
        val probe = fixture.send(tools.single { it.path == "/call_audio_probe" }).result
        assertEquals(JsonPrimitive("offhook"), probe["call_state"])
        assertEquals(1, (fixture.inputs.probe as FakeAudioPorts).plans.size)
    }

    @Test fun parameterDependentPlainWaitAndVolumeWorkDisconnectedWhilePowerKeepsItsAccessibilityNeed() = runTest {
        val fixture = Fixture(testScheduler)
        fixture.tree.connection = PortResult.Success(false)
        assertEquals(JsonPrimitive(true), fixture.post("/wait", """{"timeoutMs":1}""").result["success"])
        for (key in listOf("volume_up", "volume_down")) {
            assertEquals(JsonPrimitive(true), fixture.post("/press_key", """{"key":"$key"}""").result["success"])
        }
        assertEquals(listOf(VolumeDirection.UP, VolumeDirection.DOWN), fixture.volume)
        assertEquals(JsonPrimitive("accessibility_disabled"), fixture.post("/press_key", """{"key":"power"}""").result["code"])
    }

    @Test fun actualEventFeedAndComposedRoutesUseTheSameBufferAndReplayDoesNotClearIt() = runTest {
        val fixture = Fixture(testScheduler)
        val toggle = tools.single { it.path == "/events/stream" }
        fixture.send(toggle, id = "stream-id")
        val event = mockk<AccessibilityEvent>()
        every { event.eventType } returns AccessibilityEvent.TYPE_VIEW_CLICKED
        every { event.eventTime } returns fixture.inputs.clock.monotonicMs()
        every { event.isPassword } returns false
        every { event.packageName } returns "fixture.app"
        every { event.className } returns "Fixture"
        every { event.text } returns mutableListOf("private event")
        every { event.contentDescription } returns null
        EventFeed(fixture.inputs.events, fixture.logger).onEvent(event)
        fixture.send(toggle, id = "stream-id")
        val response = fixture.send(tools.single { it.path == "/events" })
        assertEquals(JsonPrimitive(1), response.result["count"])
        assertEquals(JsonPrimitive("private event"), response.result["entries"]!!.jsonArray.single().jsonObject["text"])
        assertTrue(fixture.logger.entries.none { (it.context + it.cause?.stackTraceToString()).contains("private event") })
    }

    @Test fun composedFailuresKeepPermissionAndDeadlineResultsAndNeverRepeatTheAction() = runTest {
        val fixture = Fixture(testScheduler)
        fixture.callFailure = PortFailure(FailureKind.PERMISSION_DENIED, SecurityException("private cause"),
            permission = "android.permission.CALL_PHONE")
        val denied = fixture.send(tools.single { it.path == "/call" }).result
        assertEquals(JsonPrimitive("permission_denied"), denied["code"])
        assertEquals(JsonPrimitive("android.permission.CALL_PHONE"), denied["permission"])
        fixture.beforeSms = { awaitCancellation() }
        val tool = tools.single { it.path == "/send_sms" }
        val pending = async { fixture.send(tool, id = "timeout") }
        advanceUntilIdle()
        assertEquals(25_000, currentTime)
        assertEquals(JsonPrimitive(ErrorCode.TIMEOUT.wireValue), pending.await().result["code"])
        assertSame(pending.await(), fixture.send(tool, id = "timeout"))
        assertEquals(1, fixture.sms.size)
        assertTrue(fixture.logger.entries.none { (it.context + it.cause?.stackTraceToString()).contains("private cause") })
    }
}
