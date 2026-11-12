package tech.atomiclabs.link.service

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import tech.atomiclabs.link.core.log.Logger
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import tech.atomiclabs.link.core.command.*
import tech.atomiclabs.link.core.fakes.*
import tech.atomiclabs.link.core.events.EventBuffer
import tech.atomiclabs.link.core.port.*
import tech.atomiclabs.link.core.screen.WindowOrder
import org.junit.After
import org.junit.Assert.*
import io.mockk.mockkConstructor
import io.mockk.unmockkConstructor
import tech.atomiclabs.link.ui.AndroidSetupActions
import tech.atomiclabs.link.ui.AndroidRestrictedSettings
import tech.atomiclabs.link.R
import tech.atomiclabs.link.core.protocol.*

@OptIn(ExperimentalCoroutinesApi::class)
class LinkRuntimeTest {
    @Test fun openAppUsesTheSharedTreeAndClockThenCurrentAppReadsTheLatestForeground() = runTest {
        val clock = FakeClock(testScheduler)
        val bounds = Bounds(0, 0, 10, 10)
        val root = FakeUiNode(UiNodeProperties(bounds, 1, packageName = "other.app"))
        val tree = FakeUiTree().apply {
            windowResult = PortResult.Success(listOf(UiWindow(1, WindowType.APPLICATION, 1, 0, true, true, root, bounds)))
        }
        val source = object : UiTreeSource by tree {
            override suspend fun windows(): PortResult<List<UiWindow>> {
                if (clock.monotonicMs() >= 500) root.properties = root.properties.copy(packageName = "fixture.app")
                return tree.windows()
            }
        }
        var launches = 0
        val base = runtimeInputs(clock)
        val apps = object : AppControl by base.apps {
            override suspend fun launch(packageName: String): AppLaunchOutcome { launches++; return AppLaunchOutcome.Launched }
        }
        val runtime = LinkRuntime(FakeLogger(), base.copy(tree = source, apps = apps,
            activityForPackage = { if (it == "fixture.app") "fixture.app.Screen" else null }),
            featureScope(testScheduler), installFailureHandler = {})
        val command = BridgeCommand("open-runtime", "POST", "/open_app", body = buildJsonObject { put("package", "fixture.app") })
        val opened = runtime.router.dispatch(command)
        assertEquals(JsonPrimitive(500), opened.result["waited_ms"])
        assertEquals(JsonPrimitive(true), opened.result["foreground_verified"])
        assertSame(opened, runtime.router.dispatch(command))
        assertEquals(1, launches)
        val current = runtime.router.dispatch(BridgeCommand("current-runtime", "GET", "/current_app")).result
        assertEquals(JsonPrimitive("fixture.app"), current["package"])
        assertEquals(JsonPrimitive("fixture.app.Screen"), current["activity"])
    }

    @Test fun appliedTextCursorRefusalUsesTheInjectedRuntimeLogger() = runTest {
        val node = mockk<UiNode>()
        io.mockk.coEvery { node.read() } returns PortResult.Success(
            UiNodeProperties(Bounds(0, 0, 20, 20), 11, text = "", editable = true))
        io.mockk.coEvery { node.perform(any<NodeAction.SetText>()) } returns PortResult.Success(Unit)
        val cause = IllegalStateException("private-runtime-text")
        io.mockk.coEvery { node.perform(any<NodeAction.SetSelection>()) } returns
            PortResult.Failure(PortFailure(FailureKind.REJECTED, cause))
        val tree = FakeUiTree().apply { focus = ReadValue.Present(node) }
        val inputs = runtimeInputs(FakeClock(testScheduler)).copy(tree = tree)
        val runtime = LinkRuntime(logger, inputs, scope, installFailureHandler = {})
        val result = runtime.router.dispatch(tech.atomiclabs.link.core.protocol.BridgeCommand("text-runtime", "POST", "/type",
            body = kotlinx.serialization.json.buildJsonObject { put("text", kotlinx.serialization.json.JsonPrimitive("ok")) }))
        assertEquals(200, result.status)
        assertEquals(kotlinx.serialization.json.JsonPrimitive(true), result.result["success"])
        verify(exactly = 1) { logger.warn("TextEntry", "restoreCursorBestEffort", "state=declined kind=REJECTED",
            match { it != null && it.stackTrace.contentEquals(cause.stackTrace) &&
                !it.stackTraceToString().contains("private-runtime-text") }) }
        io.mockk.coVerify(exactly = 1) { node.perform(NodeAction.SetText("ok")) }
    }

    private val scheduler = TestCoroutineScheduler()
    private val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(scheduler))
    private val featureScopes = mutableListOf<CoroutineScope>()
    @After fun cleanup() { scope.cancel(); featureScopes.forEach { it.cancel() } }
    private val logger = mockk<Logger>(relaxed = true)
    private lateinit var installedHandler: Thread.UncaughtExceptionHandler
    private var terminated = false

    @Test fun startIsIdempotentAndAnUnpairedRuntimeOpensNoSocket() {
        val sockets = mockk<RelaySocketFactory>()
        val fixture = runtimeInputs(FakeClock(scheduler)).copy(sockets = sockets)
        var installs = 0
        val runtime = LinkRuntime(logger, fixture, scope, installFailureHandler = { installs++ })
        repeat(5) { runtime.start() }
        scheduler.runCurrent()
        assertEquals(1, installs)
        assertEquals(ConnectionPhase.UNPAIRED, runtime.supervisor.states.value.phase)
        assertNull(runtime.supervisor.states.value.failure)
        io.mockk.coVerify(exactly = 0) { sockets.connect(any()) }
        verify(exactly = 0) { logger.error(any(), any(), any(), any()) }
        assertEquals(false, terminated)
    }

    @Test fun uncaughtFailureIsLoggedDelegatedAndTerminatesTheProcess() {
        val cause = IllegalStateException("crash")
        val thread = Thread.currentThread()
        val previous = mockk<Thread.UncaughtExceptionHandler>(relaxed = true)
        runtime(previous).start()
        installedHandler.uncaughtException(thread, cause)
        verify { logger.error("LinkApp", "uncaughtException", "threadId=${thread.id}",
            match { it.stackTrace.contentEquals(cause.stackTrace) && !it.stackTraceToString().contains("crash") }) }
        verify { previous.uncaughtException(thread, cause) }
        assertEquals(true, terminated)
    }

    @Test fun missingPreviousHandlerStillTerminates() {
        runtime().start()
        installedHandler.uncaughtException(Thread.currentThread(), IllegalStateException("crash"))
        assertEquals(true, terminated)
    }

    @Test fun loggingFailureStillDelegatesAndTerminates() {
        val loggingFailure = IllegalStateException("logger failed")
        every { logger.error(any(), any(), any(), any()) } throws loggingFailure
        val previous = mockk<Thread.UncaughtExceptionHandler>(relaxed = true)
        runtime(previous).start()
        val cause = IllegalStateException("crash")
        val thrown = org.junit.Assert.assertThrows(IllegalStateException::class.java) {
            installedHandler.uncaughtException(Thread.currentThread(), cause)
        }
        assertSame(loggingFailure, thrown)
        verify { previous.uncaughtException(any(), cause) }
        assertEquals(true, terminated)
    }

    @Test fun previousHandlerFailureStillTerminates() {
        val previous = mockk<Thread.UncaughtExceptionHandler>()
        val handlerFailure = IllegalStateException("previous handler failed")
        every { previous.uncaughtException(any(), any()) } throws handlerFailure
        runtime(previous).start()
        val thrown = org.junit.Assert.assertThrows(IllegalStateException::class.java) {
            installedHandler.uncaughtException(Thread.currentThread(), IllegalStateException("crash"))
        }
        assertSame(handlerFailure, thrown)
        assertEquals(true, terminated)
    }

    private fun runtime(previous: Thread.UncaughtExceptionHandler? = null) = LinkRuntime(
        logger = logger,
        inputs = runtimeInputs(FakeClock(scheduler)),
        scope = scope,
        installFailureHandler = { installedHandler = it },
        previousFailureHandler = previous,
        terminateProcess = { terminated = true },
    )

    @Test fun shutdownJoinsOwnedJobsClearsCallbackAndClosesOnlyOnce() = runTest {
        var callback: (() -> Unit)? = {}
        var closed = 0
        val inputs = runtimeInputs(FakeClock(testScheduler)).copy(
            connectedCallback = { callback = it }, close = { closed++ })
        val owned = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val runtime = LinkRuntime(logger, inputs, owned, installFailureHandler = {})
        runtime.start()
        runCurrent()
        runtime.shutdown(); runtime.shutdown()
        assertNull(callback); assertEquals(1, closed)
        assertFalse(owned.isActive)
        assertThrows(IllegalStateException::class.java) { runtime.start() }
    }

    @Test fun activityFactoryForwardsPermissionResultsThroughItsOwnActions() {
        mockkConstructor(AndroidSetupActions::class)
        try {
            every { anyConstructed<AndroidSetupActions>().onRequestPermissionsResult(any(), any(), any()) } returns Unit
            val binder = runtime().create(mockk(relaxed = true))
            binder.onRequestPermissionsResult(73, arrayOf("fixture"), intArrayOf(0))
            verify(exactly = 1) { anyConstructed<AndroidSetupActions>()
                .onRequestPermissionsResult(73, arrayOf("fixture"), intArrayOf(0)) }
            binder.destroy()
        } finally { unmockkConstructor(AndroidSetupActions::class) }
    }

    @Test fun activityFactoryInjectsRestrictionObservationThroughTheRealPresenter() {
        mockkConstructor(AndroidRestrictedSettings::class)
        try {
            io.mockk.coEvery { anyConstructed<AndroidRestrictedSettings>().read() } returns
                PortResult.Success(RestrictedSettingsStatus.RESTRICTED)
            val activity = mockk<android.app.Activity>(relaxed = true)
            val text = mockk<android.widget.TextView>(relaxed = true)
            val url = mockk<android.widget.EditText>(relaxed = true)
            val connect = mockk<android.widget.Button>(relaxed = true)
            every { activity.findViewById<android.view.View>(any()) } answers {
                when (firstArg<Int>()) {
                    R.id.relay_url -> url
                    R.id.connect -> connect
                    else -> text
                }
            }
            val binder = runtime().create(activity)
            binder.resume()
            scheduler.runCurrent()
            io.mockk.coVerify(exactly = 1) { anyConstructed<AndroidRestrictedSettings>().read() }
            verify { activity.applicationContext }
            verify { text.setText(R.string.status_restricted_blocked) }
            binder.destroy()
        } finally { unmockkConstructor(AndroidRestrictedSettings::class) }
    }

    @Test fun notificationFeedSubscribesOnceBeforeAuthenticationAndStreamsOfflineQueueWithStatusPermissions() = runTest {
        val notifications = FakeNotificationSource().apply { enabled = PortResult.Success(true) }
        var registrations = 0
        var closes = 0
        val owned = featureScope(testScheduler)
        val source = object : NotificationSource {
            override suspend fun isEnabled() = notifications.isEnabled()
            override suspend fun register(listener: (NotificationEvent) -> Unit): PortResult<NotificationSubscription> {
                registrations++
                val subscription = (notifications.register(listener) as PortResult.Success).value
                return PortResult.Success(NotificationSubscription {
                    assertFalse(owned.isActive); closes++
                    subscription.close()
                })
            }
        }
        val sockets = mutableListOf<FakeRelaySocket>()
        val inputs = runtimeInputs(FakeClock(testScheduler)).copy(notifications = source, sockets = RelaySocketFactory {
            assertEquals(1, registrations)
            PortResult.Success(FakeRelaySocket().also { sockets += it })
        })
        val runtime = LinkRuntime(FakeLogger(), inputs, owned, installFailureHandler = {})
        try {
            repeat(3) { runtime.start() }; runCurrent()
            assertEquals(1, registrations); assertTrue(sockets.isEmpty())
            repeat(201) { notifications.emit(NotificationEvent.Posted("key-$it", "fixture.other", null,
                "private-notification", null, it.toLong(), false)) }
            val supplied = (0 until 6).map { (65 + it).toChar() }.joinToString("")
            runtime.connection.pair(supplied); runCurrent()
            val first = sockets.single()
            authenticate(first)
            val sent = frames(first)
            val streamed = sent.filter { it["channel"] == JsonPrimitive("notifications") }
            assertEquals(200, streamed.size)
            assertEquals("key-1", streamed.first()["payload"]!!.jsonObject["key"]!!.jsonPrimitive.content)
            val status = sent.single { it["type"] == JsonPrimitive("bridge.status") }["payload"]!!.jsonObject
            assertEquals(JsonPrimitive(true), status["bridge"]!!.jsonObject["notification_listener_granted"])
            for (name in listOf("all_files", "microphone", "phone_state", "notification_access")) {
                assertEquals(JsonPrimitive(true), status["permissions"]!!.jsonObject[name])
            }
            first.events.emit(SocketEvent.Closed(1001, "fixture disconnect")); runCurrent()
            notifications.emit(NotificationEvent.Removed("offline", "fixture.other", 300, 1))
            runCurrent()
            assertEquals(0, closes); assertEquals(1, registrations)
            runtime.connection.connect(); runCurrent()
            val second = sockets.last()
            authenticate(second)
            assertEquals(1, frames(second).count { it["channel"] == JsonPrimitive("notifications") })
            assertEquals(200, frames(first).count { it["channel"] == JsonPrimitive("notifications") })
            assertEquals(1, registrations)
        } finally { runtime.shutdown() }
        assertEquals(1, closes)
    }

    @Test fun notificationRegistrationFailureStopsStartupAndShutdownStillClosesOtherResources() = runTest {
        val notificationCause = IllegalStateException("private-registration-failure")
        val notifications = FakeNotificationSource().apply {
            registrationFailure = PortFailure(FailureKind.PLATFORM, notificationCause)
        }
        val failures = mutableListOf<Throwable>()
        val owned = featureScope(testScheduler, CoroutineExceptionHandler { _, cause -> failures += cause })
        val log = FakeLogger()
        var closed = 0
        val runtime = LinkRuntime(log, runtimeInputs(FakeClock(testScheduler)).copy(notifications = notifications,
            close = { closed++ }), owned, installFailureHandler = {})
        runtime.start(); runCurrent()
        assertSame(notificationCause, failures.single().cause)
        assertTrue(log.entries.any { it.operation == "startNotifications" && it.level == "error" })
        assertFalse(log.entries.joinToString { it.cause?.stackTraceToString().orEmpty() }.contains(notificationCause.message!!))
        runtime.shutdown()
        assertEquals(1, closed)
    }

    @Test fun shutdownCancelsStreamsBeforeSubscriptionCloseAndRetainsBothCleanupFailures() = runTest {
        val notifications = FakeNotificationSource().apply {
            closeResult = PortResult.Failure(PortFailure(FailureKind.PLATFORM, IllegalStateException("private-feed-close")))
        }
        val clientCause = IllegalStateException("private-client-close")
        val owned = featureScope(testScheduler)
        var closed = 0
        val runtime = LinkRuntime(FakeLogger(), runtimeInputs(FakeClock(testScheduler)).copy(notifications = notifications,
            close = { assertFalse(owned.isActive); closed++; throw clientCause }), owned, installFailureHandler = {})
        runtime.start(); runCurrent()
        var failure: Exception? = null
        try { runtime.shutdown() } catch (cause: IllegalStateException) { failure = cause }
        val causes = generateSequence<Throwable>(failure!!) { it.cause }.toList()
        assertTrue(causes.any { it === (notifications.closeResult as PortResult.Failure).failure.cause })
        assertTrue(causes.flatMap { it.suppressed.toList() }.any { it === clientCause })
        assertEquals(1, closed)
        runtime.shutdown()
        assertEquals(1, closed)
    }

    @Test fun composedNewRouteDeadlinesAre117And280SecondsWhileExistingCommandsRemain25Seconds() = runTest {
        val base = runtimeInputs(FakeClock(testScheduler))
        var resolutions = 0
        val files = object : FileAccess by base.files {
            override suspend fun resolve(path: String): FileResult<String> { resolutions++; awaitCancellation() }
        }
        val runtime = LinkRuntime(FakeLogger(), base.copy(files = files,
            probe = AudioProbe { awaitCancellation() }, sms = SmsSender { _, _ -> awaitCancellation() }),
            featureScope(testScheduler), installFailureHandler = {})
        suspend fun call(path: String, body: String) = runtime.router.dispatch(BridgeCommand(path, "POST", path,
            body = Json.parseToJsonElement(body).jsonObject))
        val legacy = async { call("/send_sms", """{"to":"fixture","body":"fixture"}""") }
        val probe = async { call("/call_audio_probe", "{}") }
        val pull = async { call("/files/pull", """{"path":"fixture"}""") }
        val push = async { call("/files/push", """{"media":"fixture","name":"fixture","size":0,"sha256":"${"0".repeat(64)}"}""") }
        runCurrent(); advanceTimeBy(25_000); runCurrent()
        assertEquals(JsonPrimitive("timeout"), legacy.await().result["code"])
        assertFalse(probe.isCompleted); assertFalse(pull.isCompleted); assertFalse(push.isCompleted)
        advanceTimeBy(91_999); runCurrent(); assertFalse(probe.isCompleted)
        advanceTimeBy(1); runCurrent()
        assertEquals(JsonPrimitive("timeout"), probe.await().result["code"])
        assertFalse(pull.isCompleted); assertFalse(push.isCompleted)
        advanceTimeBy(162_999); runCurrent(); assertFalse(pull.isCompleted); assertFalse(push.isCompleted)
        advanceTimeBy(1); runCurrent()
        assertEquals(JsonPrimitive("timeout"), pull.await().result["code"])
        assertEquals(JsonPrimitive("timeout"), push.await().result["code"])
        assertEquals(280_000, currentTime); assertEquals(2, resolutions)
        assertSame(pull.await(), call("/files/pull", """{"path":"fixture"}"""))
        assertEquals(2, resolutions)
    }

    @Test fun composedProbeCanCompleteBeyond25SecondsAndUnknownPermissionFailureKeepsNullMetadata() = runTest {
        val base = runtimeInputs(FakeClock(testScheduler))
        val fake = FakeAudioPorts()
        val runtime = LinkRuntime(FakeLogger(), base.copy(probe = AudioProbe { delay(30_000); fake.run(it) }),
            featureScope(testScheduler), installFailureHandler = {})
        val pending = async { runtime.router.dispatch(BridgeCommand("long-probe", "POST", "/call_audio_probe")) }
        advanceTimeBy(25_000); runCurrent(); assertFalse(pending.isCompleted)
        advanceTimeBy(5_000); runCurrent()
        assertEquals(JsonPrimitive(true), pending.await().result["success"])
        assertEquals(30_000, currentTime)
        val queryCause = SecurityException("permission query failed")
        val unreadable = LinkRuntime(FakeLogger(), base.copy(permissions = PermissionState {
            PortResult.Failure(PortFailure(FailureKind.PERMISSION_DENIED, queryCause))
        }), featureScope(testScheduler), installFailureHandler = {})
        val result = unreadable.router.dispatch(BridgeCommand("failed-query", "POST", "/call_audio_probe")).result
        assertEquals(JsonPrimitive("audio_unavailable"), result["code"])
        assertFalse(result.containsKey("permission"))
        assertEquals(JsonPrimitive(queryCause.message), result["cause"])
    }

    private fun featureScope(scheduler: TestCoroutineScheduler, handler: CoroutineExceptionHandler? = null): CoroutineScope {
        val context = SupervisorJob() + StandardTestDispatcher(scheduler)
        return CoroutineScope(if (handler == null) context else context + handler).also { featureScopes += it }
    }

    private suspend fun TestScope.authenticate(socket: FakeRelaySocket) {
        socket.events.emit(SocketEvent.Opened); runCurrent()
        val frame = Envelope("system", "auth.ok", "fixture", buildJsonObject {
            put("session_token", "synthetic-runtime-session"); put("expires_at", JsonNull)
            putJsonObject("grants") { put("bridge", JsonNull) }
        })
        socket.events.emit(SocketEvent.Text(Json.encodeToString(Envelope.serializer(), frame)))
        runCurrent()
    }

    private fun frames(socket: FakeRelaySocket) = socket.sendAttempts.map { Json.parseToJsonElement(it).jsonObject }
}

internal fun runtimeInputs(clock: Clock): LinkRuntime.Inputs = LinkRuntime.Inputs(
    tree = FakeUiTree(), foregroundEvents = FakeForegroundEvents(), display = FakeDisplayGate(),
    capture = object : ScreenshotSource {
        override suspend fun capture(target: CaptureTarget) =
            ScreenshotOutcome.Captured(CapturedImage(2, 2, IntArray(4) { -1 }))
        override suspend fun encode(image: CapturedImage, format: ImageFormat, quality: Int) =
            PortResult.Success(EncodedImage(image.width, image.height, format, byteArrayOf(1, 2, 3)))
    },
    device = { DeviceStateSource { PortResult.Success(DeviceSnapshot(80, true, "fixture.app", "Fixture")) } },
    identity = DeviceIdentity { PortResult.Success(DeviceDescription("Fixture", "Fixture", "Model", "Android 16",
        "tech.atomiclabs.link", "1.0")) },
    permissions = PermissionState { PortResult.Success(PermissionSnapshot(true, true, true, true, true, true, true, true, true,
        allFiles = true, microphone = true, phoneState = true, notificationAccess = true)) },
    sockets = RelaySocketFactory { error("An unpaired runtime must not open a socket.") },
    network = object : NetworkMonitor {
        override val changes = kotlinx.coroutines.flow.emptyFlow<PortResult<Boolean>>()
        override suspend fun isAvailable() = PortResult.Success(true)
    },
    sessions = FakeSessionStore(),
    settings = FakeSettingsStore().apply {
        url = ReadValue.Present("ws://localhost")
        id = ReadValue.Present("11111111-2222-3333-4444-555555555555")
    },
    clock = clock,
    media = { object : MediaTransfer {
        override suspend fun upload(httpBase: String, image: EncodedImage): PortResult<UploadedMedia> = error("Unexpected upload.")
        override suspend fun fetch(httpBase: String, token: String): PortResult<FetchedMedia> = error("Unexpected fetch.")
    } },
    activityForPackage = { "Fixture" }, openStatus = { PortResult.Success(Unit) },
    connectedCallback = {}, close = {},
    gestures = GestureInput { PortResult.Success(Unit) }, globals = GlobalKeys { PortResult.Success(Unit) },
    volume = VolumeControl { PortResult.Success(Unit) },
    metrics = ScreenMetrics { PortResult.Success(DisplayMetrics(1080, 2400, 3f)) },
    power = object : PowerState {
        override suspend fun isInteractive() = PortResult.Success(true)
        override suspend fun wakeWithBrightLock(durationMs: Long) = PortResult.Success(Unit)
    },
    events = EventBuffer(clock),
    apps = object : AppControl {
        override suspend fun launchableApps() = PortResult.Success(listOf(LaunchableApp("fixture.app", "Fixture")))
        override suspend fun launch(packageName: String) = AppLaunchOutcome.Launched
        override suspend fun startActivity(intent: IntentSpec) = ActivityOutcome.Started
        override suspend fun sendBroadcast(intent: IntentSpec) = PortResult.Success(Unit)
    },
    clipboard = object : ClipboardAccess {
        override suspend fun read() = PortResult.Success("")
        override suspend fun write(text: String) = PortResult.Success(Unit)
    },
    mediaKeys = MediaKeys { PortResult.Success(85) },
    location = object : LocationSource {
        override suspend fun lastKnown() = PortResult.Success(listOf(LocationFix(1.0, 2.0, 3f, "fixture", clock.epochMs())))
        override suspend fun current(timeoutMs: Long) = ReadValue.Absent
    },
    contacts = ContactsSource { _, _ -> PortResult.Success(emptyList()) },
    calls = CallControl { PortResult.Success(Unit) }, sms = SmsSender { _, _ -> SmsOutcome.Sent(1) },
    outbound = object : OutboundFiles {
        override suspend fun prune(olderThanEpochMs: Long) = PortResult.Success(Unit)
        override suspend fun write(bytes: ByteArray, fileName: String?, contentType: String) =
            PortResult.Success(OutboundAttachment("content://tech.atomiclabs.link.files/outbound/fixture", contentType, "fixture"))
    },
    shares = ShareLauncher { ShareLaunchOutcome.Opened(it.packageName) },
    notifications = FakeNotificationSource().apply { enabled = PortResult.Success(true) },
    files = FakeFileAccess().apply {
        entry = FileEntry("fixture.bin", "Download/fixture.bin", FileKind.FILE, 3, 0)
        reader = FakeFileReadHandle(byteArrayOf(1, 2, 3))
        writer = FakeFileWriteHandle(3)
        entries = listOf("Download", "DCIM", "Pictures", "Documents", "outbound")
            .map { FileEntry(it, it, FileKind.DIRECTORY, 0, 0) }
    },
    probe = FakeAudioPorts(), serviceAvailability = {}, ioDispatcher = Dispatchers.Unconfined,
)
