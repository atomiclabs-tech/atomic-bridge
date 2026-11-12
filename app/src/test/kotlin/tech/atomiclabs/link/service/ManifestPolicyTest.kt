package tech.atomiclabs.link.service

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.*
import org.junit.Test
import org.w3c.dom.Element

class ManifestPolicyTest {
    private val namespace = "http://schemas.android.com/apk/res/android"
    private fun parse(path: String) = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
    }.newDocumentBuilder().parse(File(path))
    private val document get() = parse("src/main/AndroidManifest.xml")
    private fun elements(tag: String): List<Element> = document.getElementsByTagName(tag).let { nodes ->
        (0 until nodes.length).map { nodes.item(it) as Element }
    }
    private fun Element.attr(name: String) = getAttributeNS(namespace, name)
    private fun named(tag: String, name: String) = elements(tag).single { it.attr("name") == name }

    @Test fun permissionsAreExactlyThe21ApprovedPermissionsAndTelephonyIsOptional() {
        val names = setOf("INTERNET", "ACCESS_NETWORK_STATE", "FOREGROUND_SERVICE",
            "FOREGROUND_SERVICE_SPECIAL_USE", "POST_NOTIFICATIONS", "RECEIVE_BOOT_COMPLETED", "WAKE_LOCK",
            "TURN_SCREEN_ON", "REQUEST_IGNORE_BATTERY_OPTIMIZATIONS", "SEND_SMS", "CALL_PHONE", "READ_CONTACTS",
            "ACCESS_FINE_LOCATION", "ACCESS_COARSE_LOCATION", "ACCESS_BACKGROUND_LOCATION",
            "MANAGE_EXTERNAL_STORAGE", "RECORD_AUDIO", "MODIFY_AUDIO_SETTINGS", "READ_PHONE_STATE", "FOREGROUND_SERVICE_MICROPHONE",
            "WRITE_SECURE_SETTINGS")
            .map { "android.permission.$it" }.toSet()
        val actual = elements("uses-permission").map { it.attr("name") }
        assertEquals(names, actual.toSet())
        assertEquals(21, actual.size)
        val feature = elements("uses-feature").single()
        assertEquals("android.hardware.telephony", feature.attr("name"))
        assertEquals("false", feature.attr("required"))
    }

    @Test fun exactlySevenShellsKeepTheirLabelsExportStateBackupAndRequiredConfigs() {
        val expected = mapOf("application" to setOf(".service.LinkApp"),
            "activity" to setOf(".ui.StatusActivity", ".device.power.TrampolineActivity"),
            "service" to setOf(".device.access.LinkAccessibilityService", ".service.ConnectionService", ".device.notify.LinkNotificationListener"),
            "receiver" to setOf(".service.BootReceiver"))
        for ((tag, names) in expected) {
            val found = elements(tag).map { it.attr("name") }
            assertEquals(names, found.toSet())
            assertEquals(names.size, found.size)
            for (element in elements(tag)) {
                val expectedLabel = if (element.attr("name") == ".ui.StatusActivity") "@string/launcher_name" else "@string/app_name"
                assertEquals(expectedLabel, element.attr("label"))
            }
        }
        assertTrue(elements("activity-alias").isEmpty())
        val app = elements("application").single()
        assertEquals("false", app.attr("allowBackup"))
        assertEquals("true", app.attr("usesCleartextTraffic"))
        assertEquals("@xml/backup_rules", app.attr("fullBackupContent"))
        assertEquals("@xml/data_extraction_rules", app.attr("dataExtractionRules"))
        assertEquals("true", named("activity", ".ui.StatusActivity").attr("exported"))
        val trampoline = named("activity", ".device.power.TrampolineActivity")
        assertEquals("false", trampoline.attr("exported"))
        for (attribute in listOf("noHistory", "excludeFromRecents", "showWhenLocked", "turnScreenOn")) {
            assertEquals("true", trampoline.attr(attribute))
        }
        val access = named("service", ".device.access.LinkAccessibilityService")
        assertEquals("true", access.attr("exported"))
        assertEquals("android.permission.BIND_ACCESSIBILITY_SERVICE", access.attr("permission"))
        val accessConfig = access.getElementsByTagName("meta-data").item(0) as Element
        assertEquals("android.accessibilityservice", accessConfig.attr("name"))
        assertEquals("@xml/accessibility_config", accessConfig.attr("resource"))
        val connection = named("service", ".service.ConnectionService")
        assertEquals("false", connection.attr("exported"))
        assertEquals("specialUse|microphone", connection.attr("foregroundServiceType"))
        val subtype = connection.getElementsByTagName("property").item(0) as Element
        assertEquals("android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE", subtype.attr("name"))
        assertEquals("@string/service_type", subtype.attr("value"))
        assertEquals("true", named("receiver", ".service.BootReceiver").attr("exported"))
        assertTrue(elements("action").map { it.attr("name") }.containsAll(
            listOf("android.intent.action.BOOT_COMPLETED", "android.intent.action.MY_PACKAGE_REPLACED")))
    }

    @Test fun notificationListenerRequiresSystemBindingAndUsesCompletedSharedLabelDescription() {
        val listener = named("service", ".device.notify.LinkNotificationListener")
        assertEquals("true", listener.attr("exported"))
        assertEquals("android.permission.BIND_NOTIFICATION_LISTENER_SERVICE", listener.attr("permission"))
        assertEquals("@string/app_name", listener.attr("label"))
        assertEquals("@string/notification_listener_description_static", listener.attr("description"))
        val actions = listener.getElementsByTagName("action")
        assertEquals(1, actions.length)
        assertEquals("android.service.notification.NotificationListenerService", (actions.item(0) as Element).attr("name"))
        val generated = parse("build/generated/res/resValues/debug/values/gradleResValues.xml").getElementsByTagName("string")
        val values = (0 until generated.length).map { generated.item(it) as Element }
            .associate { it.getAttribute("name") to it.textContent }
        assertEquals("Lets ${values.getValue("app_name")} read notifications on this device.",
            values["notification_listener_description_static"])
        assertFalse(values.getValue("notification_listener_description_static").contains("%1\$s"))
    }

    @Test fun providerIsPrivateGrantEnabledAndExposesOnlyOutboundCache() {
        val provider = elements("provider").single()
        assertEquals("androidx.core.content.FileProvider", provider.attr("name"))
        assertEquals("$" + "{applicationId}.files", provider.attr("authorities"))
        assertEquals("false", provider.attr("exported"))
        assertEquals("true", provider.attr("grantUriPermissions"))
        val metadata = provider.getElementsByTagName("meta-data")
        assertEquals(1, metadata.length)
        val paths = metadata.item(0) as Element
        assertEquals("android.support.FILE_PROVIDER_PATHS", paths.attr("name"))
        assertEquals("@xml/file_paths", paths.attr("resource"))
        val resource = parse("src/main/res/xml/file_paths.xml")
        val children = resource.documentElement.childNodes
        val entries = (0 until children.length).map { children.item(it) }
            .filter { it.nodeType == org.w3c.dom.Node.ELEMENT_NODE }
        assertEquals(1, entries.size)
        val outbound = entries.single() as Element
        assertEquals("cache-path", outbound.tagName)
        assertEquals("outbound", outbound.getAttribute("name"))
        assertEquals("outbound/", outbound.getAttribute("path"))
    }

    @Test fun visibilityIsExactlyLauncherShareAndDirectCallHandlerInventory() {
        val queries = elements("queries").single()
        val intents = queries.getElementsByTagName("intent")
        val signatures = (0 until intents.length).map { index ->
            val intent = intents.item(index) as Element
            val action = (intent.getElementsByTagName("action").item(0) as Element).attr("name")
            val categories = intent.getElementsByTagName("category")
            val category = if (categories.length == 0) "" else (categories.item(0) as Element).attr("name")
            val data = intent.getElementsByTagName("data")
            val mime = if (data.length == 0) "" else (data.item(0) as Element).attr("mimeType")
            val scheme = if (data.length == 0) "" else (data.item(0) as Element).attr("scheme")
            listOf(action, category, mime, scheme)
        }
        assertEquals(4, signatures.size)
        assertEquals(setOf(listOf("android.intent.action.MAIN", "android.intent.category.LAUNCHER", "", ""),
            listOf("android.intent.action.SEND", "", "*/*", ""),
            listOf("android.intent.action.SEND_MULTIPLE", "", "*/*", ""),
            listOf("android.intent.action.CALL", "", "", "tel")), signatures.toSet())
        assertEquals(0, queries.getElementsByTagName("package").length)
        assertEquals(0, queries.getElementsByTagName("provider").length)
    }
    @Test fun pairingViewFilterIsExactAndStatusActivityUsesSingleTop() {
        val manifest = document
        val activities = manifest.getElementsByTagName("activity")
        val status = (0 until activities.length).map { activities.item(it) as Element }
            .single { it.getAttributeNS(namespace, "name") == ".ui.StatusActivity" }
        assertEquals("singleTop", status.getAttributeNS(namespace, "launchMode"))
        val filters = status.getElementsByTagName("intent-filter")
        assertEquals(2, filters.length)
        val signatures = (0 until filters.length).map { index ->
            val filter = filters.item(index) as Element
            fun values(tag: String, attr: String): List<String> = filter.getElementsByTagName(tag).let { nodes ->
                (0 until nodes.length).map { (nodes.item(it) as Element).getAttributeNS(namespace, attr) }
            }
            Triple(values("action", "name"), values("category", "name").toSet(), values("data", "scheme") to values("data", "host"))
        }
        assertEquals(setOf(
            Triple(listOf("android.intent.action.MAIN"), setOf("android.intent.category.LAUNCHER"), emptyList<String>() to emptyList<String>()),
            Triple(listOf("android.intent.action.VIEW"), setOf("android.intent.category.DEFAULT", "android.intent.category.BROWSABLE"),
                listOf("atomic") to listOf("pair")),
        ), signatures.toSet())
        val view = filters.item(1) as Element
        val data = view.getElementsByTagName("data").item(0) as Element
        assertEquals(2, data.attributes.length)
        assertEquals(2, view.getElementsByTagName("category").length)
        assertEquals(1, view.getElementsByTagName("action").length)
    }


}
