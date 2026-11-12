package tech.atomiclabs.link.device.access

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.view.accessibility.*
import io.mockk.*
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import tech.atomiclabs.link.core.fakes.FakeLogger
import tech.atomiclabs.link.core.fakes.FakeClock
import tech.atomiclabs.link.core.events.EventBuffer
import tech.atomiclabs.link.core.port.*

class AccessibilityHubTest {
    @Test fun windowStateTimesUseTheInjectedClockPerPackageBeforeResolutionAndClearOnUnbind() = runTest {
        val clock = FakeClock(testScheduler)
        val resolver = testResolver { _, _ -> false }
        val hub = AccessibilityHub(FakeLogger(), clock, StandardTestDispatcher(testScheduler), activityResolver = resolver)
        val old = mockk<AccessibilityService>()
        val current = mockk<AccessibilityService>()
        hub.onConnected(old)
        hub.onConnected(current)
        val event = mockk<AccessibilityEvent>()
        every { event.eventType } returns AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        every { event.packageName } returns "example.first"
        every { event.className } returns "android.widget.FrameLayout"
        clock.sleep(125)
        hub.onEvent(event)
        assertEquals(125L, hub.lastWindowStateAt("example.first"))
        assertNull(hub.activityForPackage("example.first"))
        every { event.packageName } returns "example.second"
        every { event.className } returns null
        clock.sleep(75)
        hub.onEvent(event)
        assertEquals(200L, hub.lastWindowStateAt("example.second"))
        assertEquals(125L, hub.lastWindowStateAt("example.first"))
        every { event.packageName } returns "example.first"
        clock.sleep(50)
        hub.onEvent(event)
        assertEquals(250L, hub.lastWindowStateAt("example.first"))
        every { event.eventType } returns AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
        clock.sleep(50)
        hub.onEvent(event)
        hub.onEvent(null)
        hub.onUnbind(old)
        assertEquals(250L, hub.lastWindowStateAt("example.first"))
        assertNull(hub.lastWindowStateAt("example.absent"))
        hub.onUnbind(current)
        assertNull(hub.lastWindowStateAt("example.first"))
        assertNull(hub.lastWindowStateAt("example.second"))
    }

    @Test fun launcherHomeResolvesAValidatedClassWithoutAWindowStateEvent() = runTest {
        mockkConstructor(Intent::class, ComponentName::class)
        try {
            val packages = mockk<PackageManager>()
            val service = mockk<AccessibilityService>()
            val info = ActivityInfo().apply { packageName = "example.home"; name = ".HomeActivity" }
            every { service.packageManager } returns packages
            every { constructedWith<Intent>(EqMatcher(Intent.ACTION_MAIN)).addCategory(Intent.CATEGORY_HOME) } answers { self as Intent }
            every { constructedWith<Intent>(EqMatcher(Intent.ACTION_MAIN)).setPackage("example.home") } answers { self as Intent }
            every { packages.resolveActivity(any<Intent>(), PackageManager.MATCH_DEFAULT_ONLY) } returns
                ResolveInfo().apply { activityInfo = info }
            every { packages.getActivityInfo(any<ComponentName>(), 0) } returns info
            val hub = AccessibilityHub(FakeLogger(), FakeClock(testScheduler), StandardTestDispatcher(testScheduler))
            hub.onConnected(service)
            assertEquals("example.home.HomeActivity", hub.activityForPackage("example.home"))
            verify { constructedWith<Intent>(EqMatcher(Intent.ACTION_MAIN)).addCategory(Intent.CATEGORY_HOME) }
            verify { constructedWith<Intent>(EqMatcher(Intent.ACTION_MAIN)).setPackage("example.home") }
            verify(exactly = 1) { packages.getActivityInfo(any<ComponentName>(), 0) }
            verify(exactly = 0) { packages.getLaunchIntentForPackage(any()) }
        } finally { unmockkConstructor(Intent::class, ComponentName::class) }
    }

    @Test fun injectedEventFeedSeesAllKeptTypesBeforeTheActivityTrackingFilter() = runTest {
        val logger = FakeLogger()
        val buffer = EventBuffer(FakeClock(testScheduler))
        buffer.setStreaming(true)
        val hub = AccessibilityHub(logger, FakeClock(testScheduler), StandardTestDispatcher(testScheduler), EventFeed(buffer, logger),
            testResolver { _, _ -> true })
        val types = listOf(AccessibilityEvent.TYPE_VIEW_CLICKED, AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED)
        for (type in types) {
            val event = mockk<AccessibilityEvent>(relaxed = true)
            every { event.eventType } returns type
            every { event.packageName } returns "example"
            every { event.className } returns if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) "Activity" else "Field"
            hub.onEvent(event)
        }
        hub.onEvent(null)
        assertEquals(EventBuffer.Type.entries, buffer.recent().entries.map { it.type })
        assertEquals("Activity", hub.activityForPackage("example"))
        assertTrue(logger.entries.isEmpty())
    }

    @Test fun serviceAccessReturnsTheRealCurrentInstanceAndStaleUnbindCannotRemoveIt() = runTest {
        val logger = FakeLogger()
        val hub = AccessibilityHub(logger, FakeClock(testScheduler), StandardTestDispatcher(testScheduler))
        val unavailable = hub.currentService() as PortResult.Failure
        assertEquals(FailureKind.UNAVAILABLE, unavailable.failure.kind)
        val error = logger.entries.single()
        assertEquals("error", error.level)
        assertEquals("currentService", error.operation)
        assertNotNull(error.cause)
        val old = mockk<AccessibilityService>()
        val current = mockk<AccessibilityService>()
        hub.onConnected(old)
        assertSame(old, (hub.currentService() as PortResult.Success).value)
        hub.onConnected(current)
        hub.onUnbind(old)
        assertSame(current, (hub.currentService() as PortResult.Success).value)
        hub.onUnbind(current)
        assertEquals(FailureKind.UNAVAILABLE, (hub.currentService() as PortResult.Failure).failure.kind)
        assertEquals(2, logger.entries.count { it.level == "error" })
    }

    @Test fun registeredCallbackRunsAfterPublicationOnEachConnectionAndCanBeReplacedOrCleared() = runTest {
        val hub = AccessibilityHub(FakeLogger(), FakeClock(testScheduler), StandardTestDispatcher(testScheduler))
        val services = mutableListOf<AccessibilityService>()
        hub.setConnectedCallback { services += (hub.currentService() as PortResult.Success).value }
        assertTrue(services.isEmpty())
        val first = mockk<AccessibilityService>()
        val second = mockk<AccessibilityService>()
        hub.onConnected(first)
        hub.onUnbind(first)
        hub.onConnected(second)
        assertEquals(2, services.size)
        assertSame(first, services[0])
        assertSame(second, services[1])
        var replacements = 0
        hub.setConnectedCallback { replacements++ }
        assertEquals(0, replacements)
        hub.onConnected(first)
        assertEquals(1, replacements)
        hub.setConnectedCallback(null)
        hub.onConnected(second)
        assertEquals(1, replacements)
        assertEquals(2, services.size)
    }

    @Test fun callbackFailureLogsSanitizedGraphAndLeavesTheCurrentServiceUsable() = runTest {
        val logger = FakeLogger()
        val hub = AccessibilityHub(logger, FakeClock(testScheduler), StandardTestDispatcher(testScheduler))
        val original = IllegalStateException("private-callback-value", SecurityException("private-cause-value"))
        original.addSuppressed(IllegalArgumentException("private-suppressed-value"))
        hub.setConnectedCallback { throw original }
        val service = mockk<AccessibilityService>()
        hub.onConnected(service)
        assertSame(service, (hub.currentService() as PortResult.Success).value)
        val entry = logger.entries.single { it.level == "error" }
        assertEquals("AccessibilityHub", entry.component)
        assertEquals("connectedCallback", entry.operation)
        assertEquals("state=connected", entry.context)
        val logged = checkNotNull(entry.cause)
        assertArrayEquals(original.stackTrace, logged.stackTrace)
        assertNotNull(logged.cause)
        assertArrayEquals(original.cause!!.stackTrace, logged.cause!!.stackTrace)
        assertEquals(1, logged.suppressed.size)
        assertArrayEquals(original.suppressed.single().stackTrace, logged.suppressed.single().stackTrace)
        for (marker in listOf("private-callback-value", "private-cause-value", "private-suppressed-value")) {
            assertFalse(logged.stackTraceToString().contains(marker))
            assertFalse(entry.context.contains(marker))
        }
        var recovered = false
        hub.setConnectedCallback { recovered = true }
        hub.onConnected(service)
        assertTrue(recovered)
    }

    @Test fun lifecycleRetainsNewerConnectionAndWindowStateActivityBelongsToItsPackage() = runTest {
        val logger = FakeLogger()
        val hub = AccessibilityHub(logger, FakeClock(testScheduler), StandardTestDispatcher(testScheduler),
            activityResolver = testResolver { _, _ -> true })
        val old = mockk<AccessibilityService>(relaxed = true)
        val current = mockk<AccessibilityService>(relaxed = true)
        assertEquals(PortResult.Success(false), hub.isConnected())
        hub.onConnected(old)
        hub.onConnected(current)
        hub.onUnbind(old)
        assertEquals(PortResult.Success(true), hub.isConnected())
        val event = mockk<AccessibilityEvent>(relaxed = true)
        every { event.eventType } returns AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        every { event.packageName } returns "example.app"
        every { event.className } returns "ExampleActivity"
        hub.onEvent(event)
        assertEquals("ExampleActivity", hub.activityForPackage("example.app"))
        assertNull(hub.activityForPackage("com.android.systemui"))
        every { event.eventType } returns AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
        every { event.className } returns "TextField"
        hub.onEvent(event)
        hub.onEvent(null)
        assertEquals("ExampleActivity", hub.activityForPackage("example.app"))
        hub.onInterrupt()
        hub.onUnbind(current)
        assertEquals(PortResult.Success(false), hub.isConnected())
        assertNull(hub.activityForPackage("example.app"))
    }

    @Test fun everyWindowReadClearsTheCacheBeforeReadingTheService() = runTest {
        val hub = AccessibilityHub(FakeLogger(), FakeClock(testScheduler), StandardTestDispatcher(testScheduler))
        val service = mockk<AccessibilityService>()
        every { service.clearCache() } returns true
        every { service.windows } returns emptyList()
        hub.onConnected(service)
        repeat(2) { assertEquals(PortResult.Success(emptyList<UiWindow>()), hub.windows()) }
        verifySequence {
            service.clearCache(); service.windows
            service.clearCache(); service.windows
        }
    }

    @Test fun everyActiveRootAndInputFocusReadClearsTheCacheBeforeTheRootAndFocus() = runTest {
        val hub = AccessibilityHub(FakeLogger(), FakeClock(testScheduler), StandardTestDispatcher(testScheduler))
        val service = mockk<AccessibilityService>()
        val root = mockk<AccessibilityNodeInfo>()
        every { service.clearCache() } returns true
        every { service.rootInActiveWindow } returns root
        every { root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) } returns root
        hub.onConnected(service)
        repeat(2) {
            assertTrue(hub.activeRoot() is ReadValue.Present)
            assertTrue(hub.inputFocus() is ReadValue.Present)
        }
        verifySequence {
            service.clearCache(); service.rootInActiveWindow
            service.clearCache(); service.rootInActiveWindow; root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            service.clearCache(); service.rootInActiveWindow
            service.clearCache(); service.rootInActiveWindow; root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        }
    }

    @Test fun refusedCacheClearWarnsOnEveryReadAndKeepsAllThreeReadResults() = runTest {
        val logger = FakeLogger()
        val hub = AccessibilityHub(logger, FakeClock(testScheduler), StandardTestDispatcher(testScheduler))
        val service = mockk<AccessibilityService>()
        every { service.clearCache() } returns false
        every { service.windows } returns emptyList()
        every { service.rootInActiveWindow } returns null
        hub.onConnected(service)
        repeat(2) {
            assertEquals(PortResult.Success(emptyList<UiWindow>()), hub.windows())
            assertEquals(ReadValue.Absent, hub.activeRoot())
            assertEquals(ReadValue.Absent, hub.inputFocus())
        }
        val warnings = logger.entries.filter { it.level == "warn" }
        assertEquals(listOf("windows", "activeRoot", "inputFocus", "windows", "activeRoot", "inputFocus"),
            warnings.map { it.operation })
        assertTrue(warnings.all { it.component == "AccessibilityHub" && it.context == "state=cache_not_cleared" })
        assertFalse(logger.entries.any { it.level == "error" })
    }

    @Test fun thrownCacheClearFailsEveryReadWithTheCauseBeforeAccessingPlatformNodes() = runTest {
        val logger = FakeLogger()
        val hub = AccessibilityHub(logger, FakeClock(testScheduler), StandardTestDispatcher(testScheduler))
        val service = mockk<AccessibilityService>()
        val cause = IllegalStateException("private-cache-content", SecurityException("private-cache-cause"))
        every { service.clearCache() } throws cause
        hub.onConnected(service)
        val failures = listOf((hub.windows() as PortResult.Failure).failure,
            (hub.activeRoot() as ReadValue.Unreadable).failure,
            (hub.inputFocus() as ReadValue.Unreadable).failure)
        assertTrue(failures.all { problem -> problem.kind == FailureKind.PLATFORM &&
            generateSequence(problem.cause) { it.cause }.any { it === cause } })
        verify(exactly = 0) { service.windows; service.rootInActiveWindow }
        val errors = logger.entries.filter { it.level == "error" }
        assertEquals(listOf("windows", "activeRoot", "inputFocus"), errors.map { it.operation })
        assertTrue(errors.all { entry -> generateSequence(entry.cause) { it.cause }
            .any { it.stackTrace.contentEquals(cause.stackTrace) && it.cause != null } })
        assertFalse(errors.any { it.cause!!.stackTraceToString().contains("private-cache") })
    }

    @Test fun rejectedWindowClassesKeepThePreviousActivityAndAcceptedClassesReplaceIt() = runTest {
        val resolved = mutableListOf<Pair<String, String>>()
        val hub = AccessibilityHub(FakeLogger(), FakeClock(testScheduler), StandardTestDispatcher(testScheduler),
            activityResolver = testResolver { pkg, cls -> resolved += pkg to cls; cls.startsWith("$pkg.") })
        val event = mockk<AccessibilityEvent>()
        every { event.eventType } returns AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        every { event.packageName } returns "example.app"
        every { event.className } returns "example.app.FirstActivity"
        hub.onEvent(event)
        assertEquals("example.app.FirstActivity", hub.activityForPackage("example.app"))
        every { event.className } returns "android.widget.FrameLayout"
        hub.onEvent(event)
        assertEquals("example.app.FirstActivity", hub.activityForPackage("example.app"))
        every { event.className } returns "example.app.NextActivity"
        hub.onEvent(event)
        assertEquals("example.app.NextActivity", hub.activityForPackage("example.app"))
        assertEquals(listOf("example.app.FirstActivity", "android.widget.FrameLayout",
            "example.app.NextActivity"), resolved.map { it.second })
        assertTrue(resolved.all { it.first == "example.app" })
    }

    @Test fun rejectedClassWithoutAnyKnownActivityLeavesThePackageActivityNull() = runTest {
        val hub = AccessibilityHub(FakeLogger(), FakeClock(testScheduler), StandardTestDispatcher(testScheduler),
            activityResolver = testResolver { _, _ -> false })
        val event = mockk<AccessibilityEvent>()
        every { event.eventType } returns AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        every { event.packageName } returns "example.app"
        every { event.className } returns "android.widget.FrameLayout"
        hub.onEvent(event)
        assertNull(hub.activityForPackage("example.app"))
    }

    @Test fun validatedWindowEventOutranksAnUncachedLaunchEntryAndWidgetsCannotReplaceIt() = runTest {
        val resolver = mockk<ActivityResolver>()
        every { resolver.resolveActivity("example.app", null) } returns "example.app.MainActivity"
        every { resolver.resolveActivity("example.app", ".ConversationActivity") } returns "example.app.ConversationActivity"
        every { resolver.resolveActivity("example.app", "android.widget.FrameLayout") } returns null
        val hub = AccessibilityHub(FakeLogger(), FakeClock(testScheduler), StandardTestDispatcher(testScheduler), activityResolver = resolver)
        assertNull(hub.activityForPackage("example.app"))
        verify { resolver wasNot Called }
        val service = mockk<AccessibilityService>()
        hub.onConnected(service)
        repeat(2) { assertEquals("example.app.MainActivity", hub.activityForPackage("example.app")) }
        val event = mockk<AccessibilityEvent>()
        every { event.eventType } returns AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        every { event.packageName } returns "example.app"
        every { event.className } returns ".ConversationActivity"
        hub.onEvent(event)
        assertEquals("example.app.ConversationActivity", hub.activityForPackage("example.app"))
        every { event.className } returns "android.widget.FrameLayout"
        hub.onEvent(event)
        assertEquals("example.app.ConversationActivity", hub.activityForPackage("example.app"))
        verify(exactly = 2) { resolver.resolveActivity("example.app", null) }
        hub.onUnbind(service)
        assertNull(hub.activityForPackage("example.app"))
    }

    @Test fun relativeValidatedEventsRemainCanonicalWhenLaterPlatformLookupsFail() = runTest {
        mockkConstructor(ComponentName::class)
        try {
            val packages = mockk<PackageManager>()
            val service = mockk<AccessibilityService>()
            every { service.packageManager } returns packages
            every { packages.getActivityInfo(any<ComponentName>(), 0) } returns ActivityInfo().apply {
                packageName = "example.app"; name = ".ConversationActivity"
            }
            val logger = FakeLogger()
            val hub = AccessibilityHub(logger, FakeClock(testScheduler), StandardTestDispatcher(testScheduler))
            hub.onConnected(service)
            val event = mockk<AccessibilityEvent>()
            every { event.eventType } returns AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            every { event.packageName } returns "example.app"
            every { event.className } returns ".ConversationActivity"
            hub.onEvent(event)
            assertEquals("example.app.ConversationActivity", hub.activityForPackage("example.app"))
            every { event.className } returns "android.widget.FrameLayout"
            every { packages.getActivityInfo(any<ComponentName>(), 0) } throws PackageManager.NameNotFoundException("private-widget")
            hub.onEvent(event)
            val cause = SecurityException("private-event", IllegalStateException("private-event-cause"))
            every { event.className } returns ".OtherActivity"
            every { packages.getActivityInfo(any<ComponentName>(), 0) } throws cause
            hub.onEvent(event)
            assertEquals("example.app.ConversationActivity", hub.activityForPackage("example.app"))
            val failure = logger.entries.single { it.level == "error" }
            assertEquals("resolveActivity", failure.operation)
            assertArrayEquals(cause.stackTrace, failure.cause!!.stackTrace)
            assertNotNull(failure.cause.cause)
            assertFalse(failure.cause.stackTraceToString().contains("private-"))
            verify(exactly = 0) { packages.resolveActivity(any<Intent>(), any<Int>()); packages.getLaunchIntentForPackage(any()) }
        } finally { unmockkConstructor(ComponentName::class) }
    }

    @Test fun badWindowRootIsSkippedAndLoggedWhileOtherWindowsStillRead() = runTest {
        val logger = FakeLogger()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val hub = AccessibilityHub(logger, FakeClock(testScheduler), dispatcher)
        val service = mockk<AccessibilityService>(relaxed = true)
        val broken = mockk<AccessibilityWindowInfo>(relaxed = true)
        val good = mockk<AccessibilityWindowInfo>(relaxed = true)
        val absent = mockk<AccessibilityWindowInfo>(relaxed = true)
        val root = mockk<AccessibilityNodeInfo>(relaxed = true)
        every { broken.id } returns 3
        every { broken.root } throws IllegalStateException("private-root-content")
        every { good.id } returns 7
        every { good.type } returns AccessibilityWindowInfo.TYPE_APPLICATION
        every { good.root } returns root
        every { absent.root } returns null
        every { service.windows } returns listOf(broken, absent, good)
        hub.onConnected(service)
        val result = hub.windows() as PortResult.Success
        assertEquals(listOf(7), result.value.map { it.id })
        val logged = logger.entries.single { it.level == "error" }
        assertEquals("AndroidUiWindow", logged.component)
        assertTrue(logged.context.contains("window_id=3"))
        assertFalse(logged.cause!!.stackTraceToString().contains("private-root-content"))
    }

    @Test fun activeRootAndInputFocusKeepRealWindowIdAndDistinguishAbsentUnreadableDisconnected() = runTest {
        val logger = FakeLogger()
        val hub = AccessibilityHub(logger, FakeClock(testScheduler), StandardTestDispatcher(testScheduler))
        assertTrue(hub.activeRoot() is ReadValue.Unreadable)
        assertEquals(FailureKind.UNAVAILABLE, (hub.windows() as PortResult.Failure).failure.kind)
        val service = mockk<AccessibilityService>(relaxed = true)
        val root = mockk<AccessibilityNodeInfo>(relaxed = true)
        every { root.windowId } returns 91
        every { root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) } returns root
        every { service.rootInActiveWindow } returns root
        hub.onConnected(service)
        val active = (hub.activeRoot() as ReadValue.Present).value
        assertEquals(91, (active.read() as PortResult.Success).value.windowId)
        assertTrue(hub.inputFocus() is ReadValue.Present)
        every { service.rootInActiveWindow } returns null
        assertEquals(ReadValue.Absent, hub.activeRoot())
        assertEquals(ReadValue.Absent, hub.inputFocus())
        val cause = SecurityException("private-service-content")
        every { service.rootInActiveWindow } throws cause
        val failure = (hub.activeRoot() as ReadValue.Unreadable).failure
        assertTrue(generateSequence(failure.cause) { it.cause }.any { it === cause })
        every { service.windows } throws cause
        assertEquals(FailureKind.PERMISSION_DENIED, (hub.windows() as PortResult.Failure).failure.kind)
        assertFalse(logger.entries.any { it.cause?.stackTraceToString()?.contains("private-service-content") == true })
    }

    @Test fun accessibilityConfigurationHasOnlyTheApprovedCapabilitiesEventsAndFlags() {
        val document = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(File("src/main/res/xml/accessibility_config.xml"))
        val config = document.documentElement
        fun attribute(name: String) = config.getAttributeNS("http://schemas.android.com/apk/res/android", name)
        for (name in listOf("canRetrieveWindowContent", "canPerformGestures", "canTakeScreenshot", "isAccessibilityTool")) {
            assertEquals("true", attribute(name))
        }
        assertEquals(setOf("flagDefault", "flagRetrieveInteractiveWindows", "flagReportViewIds"),
            attribute("accessibilityFlags").split('|').toSet())
        assertEquals(setOf("typeWindowStateChanged", "typeWindowsChanged", "typeWindowContentChanged",
            "typeViewClicked", "typeViewTextChanged", "typeViewScrolled"), attribute("accessibilityEventTypes").split('|').toSet())
        assertEquals("100", attribute("notificationTimeout"))
        assertEquals("@string/accessibility_description", attribute("description"))
        val strings = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File("src/main/res/values/strings_access.xml"))
        assertEquals("@string/app_name", strings.getElementsByTagName("string").item(0).textContent)
    }
    private fun testResolver(valid: (String, String) -> Boolean) = object : ActivityResolver {
        override fun isActivity(packageName: String, className: String) = valid(packageName, className)
        override fun resolveActivity(packageName: String, className: String?): String? =
            className?.takeIf { valid(packageName, it) }
    }

}
