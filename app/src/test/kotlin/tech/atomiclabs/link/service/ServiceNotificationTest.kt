package tech.atomiclabs.link.service

import android.app.*
import android.content.Context
import android.content.Intent
import io.mockk.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import tech.atomiclabs.link.R
import tech.atomiclabs.link.core.fakes.FakeLogger

class ServiceNotificationTest {
    @Test fun manifestWiresTheExactStartupAndPlatformContracts() {
        val factory = javax.xml.parsers.DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val doc = factory.newDocumentBuilder().parse(java.io.File("src/main/AndroidManifest.xml"))
        val ns = "http://schemas.android.com/apk/res/android"
        fun element(tag: String, name: String): org.w3c.dom.Element {
            val nodes = doc.getElementsByTagName(tag)
            return (0 until nodes.length).map { nodes.item(it) as org.w3c.dom.Element }
                .single { it.getAttributeNS(ns, "name") == name }
        }
        val service = element("service", ".service.ConnectionService")
        assertEquals("false", service.getAttributeNS(ns, "exported"))
        assertEquals("specialUse|microphone", service.getAttributeNS(ns, "foregroundServiceType"))
        assertEquals("@string/service_type", (service.getElementsByTagName("property").item(0) as org.w3c.dom.Element)
            .getAttributeNS(ns, "value"))
        val receiver = element("receiver", ".service.BootReceiver")
        val actions = receiver.getElementsByTagName("action")
        assertEquals(setOf("android.intent.action.BOOT_COMPLETED", "android.intent.action.MY_PACKAGE_REPLACED"),
            (0 until actions.length).map { (actions.item(it) as org.w3c.dom.Element).getAttributeNS(ns, "name") }.toSet())
        val access = element("service", ".device.access.LinkAccessibilityService")
        assertEquals("android.permission.BIND_ACCESSIBILITY_SERVICE", access.getAttributeNS(ns, "permission"))
        assertEquals("@xml/accessibility_config", (access.getElementsByTagName("meta-data").item(0) as org.w3c.dom.Element)
            .getAttributeNS(ns, "resource"))
        val trampoline = element("activity", ".device.power.TrampolineActivity")
        for (flag in listOf("excludeFromRecents", "noHistory", "showWhenLocked", "turnScreenOn"))
            assertEquals(flag, "true", trampoline.getAttributeNS(ns, flag))
        assertEquals("@style/TrampolineTheme", trampoline.getAttributeNS(ns, "theme"))
        assertEquals("false", element("uses-feature", "android.hardware.telephony").getAttributeNS(ns, "required"))
        val queries = doc.getElementsByTagName("queries")
        assertEquals(1, queries.length)
        val visibility = queries.item(0) as org.w3c.dom.Element
        assertEquals(4, visibility.getElementsByTagName("intent").length)
        val queryActions = visibility.getElementsByTagName("action")
        assertEquals(4, queryActions.length)
        assertEquals(setOf("android.intent.action.MAIN", "android.intent.action.SEND",
            "android.intent.action.SEND_MULTIPLE", "android.intent.action.CALL"),
            (0 until queryActions.length).map {
                (queryActions.item(it) as org.w3c.dom.Element).getAttributeNS(ns, "name")
            }.toSet())
    }
    private val context = mockk<Context>()
    private val manager = mockk<NotificationManager>(relaxed = true)
    private val built = mockk<Notification>()
    private val pending = mockk<PendingIntent>()
    private val logger = FakeLogger()

    @Before fun setup() {
        mockkConstructor(NotificationChannel::class, Notification.Builder::class, Intent::class)
        mockkStatic(PendingIntent::class)
        every { context.getSystemService(NotificationManager::class.java) } returns manager
        every { context.getString(R.string.app_name) } returns "Atomic"
        every { context.getString(R.string.service_running) } returns "Running"
        every { PendingIntent.getActivity(context, 0, any(), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE) } returns pending
        every { channel().setSound(null, null) } just Runs
        every { channel().enableVibration(false) } just Runs
        every { channel().setShowBadge(false) } just Runs
        every { channel().lockscreenVisibility = any() } just Runs
        every { anyConstructed<Notification.Builder>().setSmallIcon(any<Int>()) } answers { self as Notification.Builder }
        every { anyConstructed<Notification.Builder>().setContentTitle(any()) } answers { self as Notification.Builder }
        every { anyConstructed<Notification.Builder>().setContentText(any()) } answers { self as Notification.Builder }
        every { anyConstructed<Notification.Builder>().setContentIntent(any()) } answers { self as Notification.Builder }
        every { anyConstructed<Notification.Builder>().setOngoing(any()) } answers { self as Notification.Builder }
        every { anyConstructed<Notification.Builder>().setDefaults(any()) } answers { self as Notification.Builder }
        every { anyConstructed<Notification.Builder>().setSound(any()) } answers { self as Notification.Builder }
        every { anyConstructed<Notification.Builder>().setVibrate(any()) } answers { self as Notification.Builder }
        every { anyConstructed<Notification.Builder>().setVisibility(any()) } answers { self as Notification.Builder }
        every { anyConstructed<Notification.Builder>().setShowWhen(any()) } answers { self as Notification.Builder }
        every { anyConstructed<Notification.Builder>().build() } returns built
    }
    @After fun cleanup() {
        unmockkConstructor(NotificationChannel::class, Notification.Builder::class, Intent::class)
        unmockkStatic(PendingIntent::class)
    }

    @Test fun minimumChannelAndNotificationAreSilentSecretOngoingAndHaveNoActions() {
        assertSame(built, ServiceNotification(context, logger).build())
        verify {
            channel().setShowBadge(false)
            channel().setSound(null, null)
            channel().enableVibration(false)
            channel().lockscreenVisibility = Notification.VISIBILITY_SECRET
            manager.createNotificationChannel(any())
            anyConstructed<Notification.Builder>().setContentTitle("Atomic")
            anyConstructed<Notification.Builder>().setContentText("Running")
            anyConstructed<Notification.Builder>().setOngoing(true)
            anyConstructed<Notification.Builder>().setDefaults(0)
            anyConstructed<Notification.Builder>().setSound(null)
            anyConstructed<Notification.Builder>().setVibrate(longArrayOf())
            anyConstructed<Notification.Builder>().setVisibility(Notification.VISIBILITY_SECRET)
            anyConstructed<Notification.Builder>().setContentIntent(pending)
        }
        verify(exactly = 0) { anyConstructed<Notification.Builder>().addAction(any<Notification.Action>()) }
        assertTrue(logger.entries.isEmpty())
    }

    @Test fun platformRefusalIsLoggedAndRethrownWithCause() {
        val cause = SecurityException("private-notification")
        every { manager.createNotificationChannel(any()) } throws cause
        val thrown = assertThrows(IllegalStateException::class.java) { ServiceNotification(context, logger).build() }
        assertSame(cause, thrown.cause)
        assertEquals("error", logger.entries.single().level)
        assertFalse(logger.entries.single().cause!!.stackTraceToString().contains("private-notification"))
    }

    private fun MockKMatcherScope.channel() = constructedWith<NotificationChannel>(
        EqMatcher("running"), EqMatcher("Atomic"), EqMatcher(NotificationManager.IMPORTANCE_MIN))
}
