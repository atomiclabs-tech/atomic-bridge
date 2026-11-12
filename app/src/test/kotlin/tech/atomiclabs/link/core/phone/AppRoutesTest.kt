package tech.atomiclabs.link.core.phone

import kotlinx.coroutines.launch
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import tech.atomiclabs.link.core.command.*
import tech.atomiclabs.link.core.fakes.*
import tech.atomiclabs.link.core.port.*
import tech.atomiclabs.link.core.protocol.BridgeCommand
import tech.atomiclabs.link.core.screen.WindowOrder

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AppRoutesTest {
    private val logger = FakeLogger()
    private val apps = Apps()
    private val gate = FakeDisplayGate()
    private val tree = FakeUiTree()
    private val foregroundEvents = FakeForegroundEvents()
    private lateinit var clock: FakeClock
    private lateinit var group: AppRoutes
    private lateinit var router: CommandRouter
    private var beforeWindowRead: suspend () -> Unit = {}
    private val readTimes = mutableListOf<Long>()
    private val source = object : UiTreeSource by tree {
        override suspend fun windows(): PortResult<List<UiWindow>> {
            readTimes += clock.monotonicMs()
            beforeWindowRead()
            return tree.windows()
        }
    }
    private var nextId = 0
    private fun TestScope.prepare(connected: Boolean = false) {
        clock = FakeClock(testScheduler)
        tree.connection = PortResult.Success(connected)
        group = AppRoutes(apps, logger, source, WindowOrder(source, logger), clock, foregroundEvents)
        router = CommandRouter(listOf(group), source, gate, logger)
    }

    private fun foreground(pkg: String) {
        val bounds = Bounds(0, 0, 10, 10)
        val root = FakeUiNode(UiNodeProperties(bounds, 1, packageName = pkg))
        tree.windowResult = PortResult.Success(listOf(UiWindow(1, WindowType.APPLICATION, 1, 0, true, true, root, bounds)))
    }
    private suspend fun call(path: String, body: String = "{}", method: String = "POST", id: String = (++nextId).toString()) =
        router.dispatch(BridgeCommand(id, method, path, body = Json.parseToJsonElement(body).jsonObject))

    @Test fun listAliasesHaveExactShapeAndNoDisplayOrAccessibilityNeed() = runTest {
        prepare()
        val first = call("/apps", method = "GET").result
        assertEquals(first, call("/get_apps", method = "GET").result)
        assertEquals(Json.parseToJsonElement("""{"apps":[{"package":"p","label":"Label"}],"count":1,"success":true}"""), first)
        assertEquals(0, gate.calls)
        assertEquals(2, apps.lists)
    }

    @Test fun launchIntentBroadcastShapesRequirementsAndReplayAreExact() = runTest {
        prepare()
        val opened = call("/open_app", """{"package":"p"}""", id = "launch")
        assertEquals(Json.parseToJsonElement("""{"package":"p","waited_ms":null,"foreground_verified":false,"success":true}"""), opened.result)
        assertEquals(opened, call("/open_app", """{"package":"p"}""", id = "launch"))
        assertEquals(1, apps.launches)
        val intent = call("/send_intent", """{"action":"VIEW","data":"sample:item","package":"p","component":"p/.Screen","category":"C","extras":{"secret":"private-extra","n":3,"b":true}}""").result
        assertEquals(Json.parseToJsonElement("""{"action":"VIEW","package":"p","component":"p/.Screen","data":"sample:item","success":true}"""), intent)
        assertEquals(IntentComponent("p", "p.Screen"), apps.last!!.component)
        assertEquals(IntentExtra.Integer(3), apps.last!!.extras["n"])
        assertEquals("C", apps.last!!.category)
        val sent = call("/broadcast", """{"action":"SIGNAL","package":"p","data":"sample:item","extras":{"flag":false,"n":2.5}}""").result
        assertEquals(Json.parseToJsonElement("""{"action":"SIGNAL","package":"p","data":"sample:item","success":true}"""), sent)
        assertEquals(IntentExtra.Decimal(2.5), apps.last!!.extras["n"])
        assertEquals(2, gate.calls)
        assertTrue(group.routes.all { !it.needs(BodyParams(JsonObject(emptyMap()))).accessibility })
    }

    @Test fun minimalIntentAndBroadcastKeepOptionalSuccessFieldsAsEmptyStrings() = runTest {
        prepare()
        assertEquals(Json.parseToJsonElement("""{"action":"VIEW","package":"","component":"","data":"","success":true}"""),
            call("/send_intent", """{"action":"VIEW"}""").result)
        assertEquals(Json.parseToJsonElement("""{"action":"SIGNAL","package":"","data":"","success":true}"""),
            call("/broadcast", """{"action":"SIGNAL"}""").result)
    }

    @Test fun absentAppsMissingHandlersAndPortErrorsAreLoggedWithoutPayloads() = runTest {
        prepare()
        apps.launchResult = AppLaunchOutcome.NotFound
        assertEquals("app_not_found", call("/open_app", """{"package":"missing"}""").result["code"]!!.jsonPrimitive.content)
        apps.activityResult = ActivityOutcome.NoHandler
        assertEquals("no_handler", call("/send_intent", """{"action":"VIEW"}""").result["code"]!!.jsonPrimitive.content)
        val failure = PortFailure(FailureKind.PERMISSION_DENIED, SecurityException("private-extra"))
        apps.broadcastResult = PortResult.Failure(failure)
        assertEquals("permission_denied", call("/broadcast", """{"action":"A","extras":{"x":"private-extra"}}""").result["code"]!!.jsonPrimitive.content)
        apps.listResult = PortResult.Failure(failure)
        assertEquals("permission_denied", call("/apps", method = "GET").result["code"]!!.jsonPrimitive.content)
        assertEquals(4, logger.entries.size)
        assertTrue(logger.entries.all { it.level == "error" && !it.cause!!.stackTraceToString().contains("private-extra") })
    }

    @Test fun invalidFieldsNeverReachPorts() = runTest {
        prepare()
        for ((path, body) in listOf("/open_app" to """{"package":""}""",
            "/send_intent" to """{"action":"A","extras":{"x":[]}}""",
            "/broadcast" to """{"action":false}""")) {
            assertEquals("bad_request", call(path, body).result["code"]!!.jsonPrimitive.content)
        }
        assertEquals(0, apps.launches); assertNull(apps.last)
        assertEquals(3, logger.entries.size)
    }

    @Test fun launchWaitsForTwoPollIntervalsThenVerifiesTheForegroundAndReplaysOnce() = runTest {
        prepare(connected = true)
        foreground("other")
        beforeWindowRead = { if (clock.monotonicMs() >= 500) foreground("p") }
        val response = call("/open_app", """{"package":"p"}""", id = "waited-launch")
        assertEquals(Json.parseToJsonElement("""{"package":"p","waited_ms":500,"foreground_verified":true,"success":true}"""), response.result)
        assertEquals(listOf(0L, 0L, 250L, 500L), readTimes)
        assertEquals(1, tree.connectionReads)
        assertEquals(1, gate.calls)
        assertSame(response, call("/open_app", """{"package":"p"}""", id = "waited-launch"))
        assertEquals(1, apps.launches)
        assertEquals(4, readTimes.size)
        assertTrue(logger.entries.isEmpty())
    }

    @Test fun neverForegroundTimesOutAfterFiveSecondsWithTheRequiredFieldsAndMessage() = runTest {
        prepare(connected = true)
        foreground("other")
        val result = call("/open_app", """{"package":"p"}""").result
        assertEquals(JsonPrimitive("timeout"), result["code"])
        assertEquals(JsonPrimitive(false), result["success"])
        assertEquals(JsonPrimitive("The app did not reach the foreground within 5 seconds."), result["error"])
        assertEquals(JsonPrimitive(5_000), result["waited_ms"])
        assertEquals(JsonPrimitive(false), result["foreground_verified"])
        assertEquals(JsonPrimitive("p"), result["package"])
        assertEquals(5_000L, clock.monotonicMs())
        assertTrue(readTimes.size >= 20)
        assertTrue(readTimes.drop(1).zipWithNext().all { (first, second) -> second - first == 250L })
        assertEquals(1, tree.connectionReads)
        assertEquals(1, apps.launches)
        assertEquals("error", logger.entries.single().level)
    }

    @Test fun alreadyForegroundReturnsImmediatelyAfterLaunching() = runTest {
        prepare(connected = true)
        foreground("p")
        val result = call("/open_app", """{"package":"p"}""").result
        assertEquals(JsonPrimitive(0), result["waited_ms"])
        assertEquals(JsonPrimitive(true), result["foreground_verified"])
        assertEquals(1, apps.launches)
        assertEquals(listOf(0L, 0L), readTimes)
    }

    @Test fun disconnectedTreeLaunchesWithoutPollingAndWarnsThatForegroundIsUnverified() = runTest {
        prepare()
        val result = call("/open_app", """{"package":"p"}""").result
        assertEquals(JsonNull, result["waited_ms"])
        assertEquals(JsonPrimitive(false), result["foreground_verified"])
        assertEquals(JsonPrimitive(true), result["success"])
        assertEquals(1, tree.connectionReads)
        assertEquals(1, apps.launches)
        assertTrue(readTimes.isEmpty())
        assertEquals(0L, clock.monotonicMs())
        val warning = logger.entries.single()
        assertEquals("warn", warning.level)
        assertEquals("AppRoutes", warning.component)
        assertEquals("package=p state=foreground_unverified", warning.context)
    }

    @Test fun failedConnectionReadLogsItsCauseAndLaunchesUnverifiedWithoutPolling() = runTest {
        prepare()
        val cause = IllegalStateException("private-connection", SecurityException("private-cause"))
        tree.connection = PortResult.Failure(PortFailure(FailureKind.PLATFORM, cause))
        val result = call("/open_app", """{"package":"p"}""").result
        assertEquals(JsonNull, result["waited_ms"])
        assertEquals(JsonPrimitive(false), result["foreground_verified"])
        assertEquals(JsonPrimitive(true), result["success"])
        assertTrue(readTimes.isEmpty())
        assertEquals(1, tree.connectionReads)
        assertEquals(listOf("error", "warn"), logger.entries.map { it.level })
        assertLoggedCause(cause)
    }

    @Test fun screenReadFailureBeforeLaunchReturnsTheExistingFailureMappingAndLogsItsCause() = runTest {
        prepare(connected = true)
        val cause = IllegalStateException("private-screen", SecurityException("private-cause"))
        tree.windowResult = PortResult.Failure(PortFailure(FailureKind.PLATFORM, cause))
        val result = call("/open_app", """{"package":"p"}""").result
        assertEquals(JsonPrimitive("internal_error"), result["code"])
        assertEquals(JsonPrimitive(false), result["success"])
        assertEquals(listOf(0L), readTimes)
        assertLoggedCause(cause)
    }

    @Test fun stalledForegroundReadStillHonorsTheFiveSecondBound() = runTest {
        prepare(connected = true)
        beforeWindowRead = { awaitCancellation() }
        val result = call("/open_app", """{"package":"p"}""").result
        assertEquals(JsonPrimitive("timeout"), result["code"])
        assertEquals(JsonPrimitive(5_000), result["waited_ms"])
        assertEquals(JsonPrimitive(false), result["foreground_verified"])
        assertEquals(5_000L, clock.monotonicMs())
    }

    @Test fun failedLaunchKeepsItsErrorAfterThePreLaunchSnapshot() = runTest {
        prepare(connected = true)
        val cause = SecurityException("private-launch")
        apps.launchResult = AppLaunchOutcome.Failed(PortFailure(FailureKind.PERMISSION_DENIED, cause))
        val result = call("/open_app", """{"package":"p"}""").result
        assertEquals(JsonPrimitive("permission_denied"), result["code"])
        assertEquals(1, tree.connectionReads)
        assertEquals(listOf(0L), readTimes)
        assertLoggedCause(cause)
    }

    @Test fun launchedPackageWindowStateAtSixHundredMsVerifiesWhileLauncherStaysInFront() = runTest {
        prepare(connected = true)
        foreground("example.launcher")
        launch {
            clock.sleep(600)
            foregroundEvents.windowStateTimes["p"] = clock.monotonicMs()
        }
        val result = call("/open_app", """{"package":"p"}""").result
        assertEquals(JsonPrimitive(true), result["success"])
        assertEquals(JsonPrimitive(true), result["foreground_verified"])
        assertTrue(result["waited_ms"]!!.jsonPrimitive.long >= 500)
        assertEquals(750L, result["waited_ms"]!!.jsonPrimitive.long)
        assertEquals("example.launcher", WindowOrder(source, logger).foregroundPackage())
        assertTrue(logger.entries.isEmpty())
    }

    @Test fun foreignActivityResumedByLaunchSucceedsUnverifiedAfterFiveSecondsWithAHint() = runTest {
        prepare(connected = true)
        foreground("example.launcher")
        val foreign = "com.google.android.settings.intelligence"
        apps.onLaunch = { foreground(foreign) }
        val result = call("/open_app", """{"package":"com.android.settings"}""").result
        assertEquals(JsonPrimitive(true), result["success"])
        assertEquals(JsonPrimitive(false), result["foreground_verified"])
        assertEquals(JsonPrimitive(5_000), result["waited_ms"])
        assertEquals(JsonPrimitive("$foreign is in front; the launched app's task may be behind it."), result["hint"])
        assertEquals(5_000L, clock.monotonicMs())
        assertNull(foregroundEvents.lastWindowStateAt("com.android.settings"))
        val warning = logger.entries.single()
        assertEquals("warn", warning.level)
        assertEquals("state=foreground_unverified package=com.android.settings front=$foreign", warning.context)
    }

    @Test fun unchangedForegroundTimesOutWithAHintAndStaleOrOtherPackageEventsDoNotVerify() = runTest {
        prepare(connected = true)
        foreground("example.launcher")
        clock.sleep(100)
        foregroundEvents.windowStateTimes["p"] = 99
        foregroundEvents.windowStateTimes["example.other"] = 100
        val result = call("/open_app", """{"package":"p"}""").result
        assertEquals(JsonPrimitive("timeout"), result["code"])
        assertEquals(JsonPrimitive(false), result["foreground_verified"])
        assertEquals(JsonPrimitive(5_000), result["waited_ms"])
        assertEquals(JsonPrimitive("example.launcher is in front."), result["hint"])
    }

    @Test fun requestedPackageAlreadyInFrontIsVerifiedWithoutWaiting() = runTest {
        prepare(connected = true)
        foreground("p")
        val result = call("/open_app", """{"package":"p"}""").result
        assertEquals(JsonPrimitive(true), result["success"])
        assertEquals(JsonPrimitive(true), result["foreground_verified"])
        assertEquals(JsonPrimitive(0), result["waited_ms"])
        assertEquals(1, apps.launches)
    }

    @Test fun serviceOffPreservesTheExactUnverifiedSuccessWithoutPolling() = runTest {
        prepare()
        val result = call("/open_app", """{"package":"p"}""").result
        assertEquals(Json.parseToJsonElement("""{"package":"p","waited_ms":null,"foreground_verified":false,"success":true}"""), result)
        assertTrue(readTimes.isEmpty())
        assertEquals("package=p state=foreground_unverified", logger.entries.single().context)
    }

    @Test fun screenFailureAfterLaunchDuringPollingReturnsInternalErrorAndLogsItsCause() = runTest {
        prepare(connected = true)
        foreground("example.launcher")
        val cause = IllegalStateException("private-poll", SecurityException("private-cause"))
        beforeWindowRead = {
            if (clock.monotonicMs() >= 250) tree.windowResult = PortResult.Failure(PortFailure(FailureKind.PLATFORM, cause))
        }
        val result = call("/open_app", """{"package":"p"}""").result
        assertEquals(JsonPrimitive("internal_error"), result["code"])
        assertEquals(JsonPrimitive(false), result["success"])
        assertEquals(250L, clock.monotonicMs())
        assertEquals(1, apps.launches)
        assertLoggedCause(cause)
    }

    @Test fun stalledPollAfterLaunchKeepsTheFiveSecondBoundAndLastCompletedForeground() = runTest {
        prepare(connected = true)
        foreground("example.launcher")
        beforeWindowRead = { if (clock.monotonicMs() >= 250) awaitCancellation() }
        val result = call("/open_app", """{"package":"p"}""").result
        assertEquals(JsonPrimitive("timeout"), result["code"])
        assertEquals(JsonPrimitive(false), result["foreground_verified"])
        assertEquals(JsonPrimitive(5_000), result["waited_ms"])
        assertEquals(JsonPrimitive("example.launcher is in front."), result["hint"])
        assertEquals(5_000L, clock.monotonicMs())
        assertEquals(listOf(0L, 0L, 250L), readTimes)
        assertEquals(1, apps.launches)
    }

    @Test fun unreadableForegroundAtExpiryHasAnUnknownHintWithoutClaimingVerification() = runTest {
        prepare(connected = true)
        val result = call("/open_app", """{"package":"p"}""").result
        assertEquals(JsonPrimitive("timeout"), result["code"])
        assertEquals(JsonPrimitive(false), result["foreground_verified"])
        assertEquals(JsonPrimitive("unknown is in front."), result["hint"])
        assertEquals(5_000L, clock.monotonicMs())
    }

    private fun assertLoggedCause(cause: Throwable) {
        val error = logger.entries.single { it.level == "error" }
        assertArrayEquals(cause.stackTrace, error.cause!!.stackTrace)
        assertEquals(cause.cause != null, error.cause.cause != null)
        assertFalse(error.cause.stackTraceToString().contains("private-"))
    }

    private class Apps : AppControl {
        var lists = 0; var launches = 0
        var onLaunch: suspend () -> Unit = {}
        var last: tech.atomiclabs.link.core.port.IntentSpec? = null
        var listResult: PortResult<List<LaunchableApp>> = PortResult.Success(listOf(LaunchableApp("p", "Label")))
        var launchResult: AppLaunchOutcome = AppLaunchOutcome.Launched
        var activityResult: ActivityOutcome = ActivityOutcome.Started
        var broadcastResult: PortResult<Unit> = PortResult.Success(Unit)
        override suspend fun launchableApps(): PortResult<List<LaunchableApp>> { lists++; return listResult }
        override suspend fun launch(packageName: String): AppLaunchOutcome { launches++; onLaunch(); return launchResult }
        override suspend fun startActivity(intent: tech.atomiclabs.link.core.port.IntentSpec): ActivityOutcome { last = intent; return activityResult }
        override suspend fun sendBroadcast(intent: tech.atomiclabs.link.core.port.IntentSpec): PortResult<Unit> { last = intent; return broadcastResult }
    }
}
