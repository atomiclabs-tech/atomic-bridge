package tech.atomiclabs.link.device.notify

import android.app.Notification
import android.content.Context
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationManagerCompat
import io.mockk.*
import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.w3c.dom.Element
import tech.atomiclabs.link.core.fakes.FakeClock
import tech.atomiclabs.link.core.fakes.FakeLogger
import tech.atomiclabs.link.core.port.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class NotificationHubTest {
    private val context = mockk<Context>()
    private val logger = FakeLogger()
    private val privateContent = "private-notification-content"
    private var enabledPackages = emptySet<String>()

    @Before fun settings() {
        every { context.packageName } returns "tech.atomiclabs.link"
        mockkStatic(NotificationManagerCompat::class)
        every { NotificationManagerCompat.getEnabledListenerPackages(context) } answers { enabledPackages }
    }

    @After fun cleanupAndAssertLogPrivacy() {
        unmockkStatic(NotificationManagerCompat::class)
        for (entry in logger.entries) {
            assertFalse((entry.component + entry.operation + entry.context).contains(privateContent))
            assertFalse(entry.cause?.stackTraceToString()?.contains(privateContent) == true)
        }
    }

    @Test fun postedFieldsAreExtractedIncludingGroupSummariesAndOptionalFields() = runTest {
        val hub = hub(testScheduler)
        val service = mockk<NotificationListenerService>()
        val received = mutableListOf<NotificationEvent>()
        hub.register { received += it }
        hub.onConnected(service)
        val posted = notification()
        hub.onPosted(service, posted)
        assertEquals(listOf(NotificationEvent.Posted("key", "example.app", "$privateContent-title",
            "$privateContent-text", "$privateContent-sub-text", 123, true, "msg")), received)
        val contents = posted.notification
        every { contents.extras.getCharSequence(any()) } returns null
        contents.category = null
        hub.onPosted(service, posted)
        assertEquals(NotificationEvent.Posted("key", "example.app", null, null, null, 123, true), received.last())
    }

    @Test fun ownPackageIsExcludedBeforeReadingContentsForPostsAndRemovals() = runTest {
        val hub = hub(testScheduler)
        val service = mockk<NotificationListenerService>()
        hub.register { fail("own notifications must be excluded") }
        hub.onConnected(service)
        val own = mockk<StatusBarNotification>()
        every { own.packageName } returns "tech.atomiclabs.link"
        hub.onPosted(service, own)
        hub.onRemoved(service, own, 7)
        verify(exactly = 0) { own.notification }
        assertEquals(1, logger.entries.size)
    }

    @Test fun removalsUseClockTimeAndKeepPlatformReasonOnlyWhenGiven() = runTest {
        val hub = hub(testScheduler)
        val service = mockk<NotificationListenerService>()
        val received = mutableListOf<NotificationEvent>()
        hub.register { received += it }
        hub.onConnected(service)
        val posted = notification()
        hub.onRemoved(service, posted, NotificationListenerService.REASON_APP_CANCEL)
        advanceTimeBy(25)
        hub.onRemoved(service, posted)
        assertEquals(listOf(NotificationEvent.Removed("key", "example.app", 500,
            NotificationListenerService.REASON_APP_CANCEL), NotificationEvent.Removed("key", "example.app", 525)), received)
    }

    @Test fun grantedRequiresSettingsAndCurrentConnectionAndIgnoresAnOldDisconnect() = runTest {
        val hub = hub(testScheduler)
        val old = mockk<NotificationListenerService>()
        val current = mockk<NotificationListenerService>()
        assertEquals(PortResult.Success(false), hub.isEnabled())
        enabledPackages = setOf("tech.atomiclabs.link")
        assertEquals(PortResult.Success(false), hub.isEnabled())
        hub.onConnected(old)
        assertEquals(PortResult.Success(true), hub.isEnabled())
        enabledPackages = emptySet()
        assertEquals(PortResult.Success(false), hub.isEnabled())
        enabledPackages = setOf("tech.atomiclabs.link")
        hub.onConnected(current)
        hub.onDisconnected(old)
        assertEquals(PortResult.Success(true), hub.isEnabled())
        hub.onDisconnected(current)
        assertEquals(PortResult.Success(false), hub.isEnabled())
    }

    @Test fun subscriptionSurvivesDisconnectButCloseAndOldOwnersPreventDelivery() = runTest {
        val hub = hub(testScheduler)
        val old = mockk<NotificationListenerService>()
        val current = mockk<NotificationListenerService>()
        val received = mutableListOf<NotificationEvent>()
        val subscription = (hub.register { received += it } as PortResult.Success).value
        val posted = notification()
        hub.onPosted(old, posted)
        hub.onConnected(old)
        hub.onPosted(old, posted)
        hub.onDisconnected(old)
        hub.onRemoved(old, posted)
        hub.onConnected(current)
        hub.onPosted(old, posted)
        hub.onPosted(current, posted)
        assertEquals(2, received.size)
        assertEquals(PortResult.Success(Unit), subscription.close())
        assertEquals(PortResult.Success(Unit), subscription.close())
        hub.onRemoved(current, posted)
        assertEquals(2, received.size)
    }

    @Test fun closingAnotherSubscriptionDuringDeliveryPreventsItsCallbackFromBeginning() = runTest {
        val hub = hub(testScheduler)
        val service = mockk<NotificationListenerService>()
        lateinit var second: NotificationSubscription
        hub.register { second.close() }
        second = (hub.register { fail("closed callback must not run") } as PortResult.Success).value
        hub.onConnected(service)
        hub.onPosted(service, notification())
    }

    @Test fun failedExtractionAndCallbacksAreLoggedWithoutContentAndDoNotBlockLaterDelivery() = runTest {
        val hub = hub(testScheduler)
        val service = mockk<NotificationListenerService>()
        val received = mutableListOf<NotificationEvent>()
        hub.register { throw IllegalStateException(privateContent) }
        hub.register { received += it }
        hub.onConnected(service)
        val broken = mockk<StatusBarNotification>()
        every { broken.packageName } throws IllegalStateException(privateContent)
        hub.onPosted(service, broken)
        hub.onPosted(service, null)
        hub.onPosted(service, notification())
        assertEquals(1, received.size)
        assertEquals(listOf("posted", "posted", "postedCallback"),
            logger.entries.filter { it.level == "error" }.map { it.operation })
    }

    @Test fun settingsFailureIsReturnedWithItsCauseAndLoggedInsteadOfBecomingDenied() = runTest {
        val hub = hub(testScheduler)
        val cause = SecurityException(privateContent)
        every { NotificationManagerCompat.getEnabledListenerPackages(context) } throws cause
        val result = hub.isEnabled() as PortResult.Failure
        assertEquals(FailureKind.PERMISSION_DENIED, result.failure.kind)
        assertTrue(generateSequence(result.failure.cause) { it.cause }.any { it === cause })
        assertEquals("isEnabled", logger.entries.single().operation)
        assertEquals("error", logger.entries.single().level)
    }

    @Test fun listenerResourcesUseTheSharedLabelAndTheApprovedDescriptionTemplate() {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File("src/main/res/values/strings_notify.xml"))
        val strings = document.getElementsByTagName("string")
        val values = (0 until strings.length).map { strings.item(it) as Element }
            .associate { it.getAttribute("name") to it.textContent }
        assertEquals("@string/app_name", values["notification_listener_label"])
        assertEquals("Lets %1\$s read notifications on this device.", values["notification_listener_description"])
        assertEquals("Lets Display Label read notifications on this device.",
            String.format(Locale.ROOT, values["notification_listener_description"]!!, "Display Label"))
    }

    private fun hub(scheduler: TestCoroutineScheduler) =
        NotificationHub(context, FakeClock(scheduler, 500), logger, StandardTestDispatcher(scheduler))

    private fun notification(): StatusBarNotification {
        val extras = mockk<Bundle>()
        every { extras.getCharSequence(Notification.EXTRA_TITLE) } returns "$privateContent-title"
        every { extras.getCharSequence(Notification.EXTRA_TEXT) } returns "$privateContent-text"
        every { extras.getCharSequence(Notification.EXTRA_SUB_TEXT) } returns "$privateContent-sub-text"
        val contents = Notification().apply {
            this.extras = extras
            category = "msg"
            flags = Notification.FLAG_GROUP_SUMMARY or Notification.FLAG_ONGOING_EVENT
        }
        return mockk<StatusBarNotification> {
            every { key } returns "key"
            every { packageName } returns "example.app"
            every { notification } returns contents
            every { postTime } returns 123
            every { isOngoing } returns true
        }
    }
}
