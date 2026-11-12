package tech.atomiclabs.link

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class LabelPolicyTest {
    private val androidNamespace = "http://schemas.android.com/apk/res/android"
    private val banned = Regex("hermes|agent", RegexOption.IGNORE_CASE)
    private val main = File("src/main")

    @Test fun allManifestLabelsDescriptionsAndComponentNamesPass() {
        val manifest = parse(File(main, "AndroidManifest.xml"))
        val elements = manifest.getElementsByTagName("*")
        for (index in 0 until elements.length) {
            val element = elements.item(index) as Element
            for (attribute in listOf("label", "description")) {
                assertAllowed("manifest ${element.tagName} $attribute", element.getAttributeNS(androidNamespace, attribute))
            }
            if (element.tagName in setOf("application", "activity", "activity-alias", "service", "receiver", "provider")) {
                for (attribute in listOf("name", "targetActivity", "parentActivityName")) {
                    assertAllowed("manifest ${element.tagName} $attribute", element.getAttributeNS(androidNamespace, attribute))
                }
            }
        }
    }

    @Test fun everyResourceStringIncludingGeneratedLabelsPasses() {
        val resources = File(main, "res").walkTopDown().filter { it.isFile && it.extension == "xml" }.toList()
        val generated = File("build/generated/res/resValues/debug/values/gradleResValues.xml")
        assertTrue("Build-generated label resource must exist", generated.isFile)
        for (file in resources + generated) {
            val document = parse(file)
            val elements = document.getElementsByTagName("*")
            for (index in 0 until elements.length) {
                val element = elements.item(index) as Element
                val holdsString = element.tagName in setOf("string", "string-array", "plurals") ||
                    (element.tagName == "item" && element.getAttribute("type") == "string")
                if (holdsString) assertAllowed(file.path, element.textContent)
            }
        }
    }

    @Test fun hermesFixtureFailsTheSameAssertionUsedForTheTree() {
        val failure = assertThrows(AssertionError::class.java) { assertAllowed("fixture", "Atomic HERMES") }
        assertTrue(failure.message!!.contains("HERMES"))
    }

    @Test fun bannedNamesAreRejectedCaseInsensitively() {
        for (name in listOf("Hermes", "HERMES", "hermes", "Agent", "AGENT", "agent")) {
            assertThrows(AssertionError::class.java) { assertAllowed("fixture", name) }
        }
        assertAllowed("fixture", "Atomic")
        assertAllowed("fixture", "Bridge")
        assertAllowed("fixture", "Atomic Bridge")
    }

    @Test fun generatedDefaultsDistinguishLauncherAndFullAppName() {
        val strings = parse(File("build/generated/res/resValues/debug/values/gradleResValues.xml"))
            .getElementsByTagName("string")
        val values = (0 until strings.length).map { strings.item(it) as Element }
            .associate { it.getAttribute("name") to it.textContent }
        assertEquals("Atomic Bridge", values["app_name"])
        assertEquals("Bridge", values["launcher_name"])
        val header = parse(File(main, "res/layout/activity_status.xml")).getElementsByTagName("TextView")
        assertTrue((0 until header.length).map { header.item(it) as Element }
            .any { it.getAttributeNS(androidNamespace, "text") == "@string/app_name" })
    }

    @Test fun killedLinkMessageDoesNotHardcodeABuildLabel() {
        val strings = parse(File(main, "res/values/strings_status.xml")).getElementsByTagName("string")
        val message = (0 until strings.length).map { strings.item(it) as Element }
            .single { it.getAttribute("name") == "status_killed_toast" }.textContent
        assertTrue("Killed-link message must preserve label overrides", !Regex("Atomic|Bridge").containsMatchIn(message))
    }

    @Test fun manifestHasExactlyTheComponentsPermissionsAndDisablesBackup() {
        val document = parse(File(main, "AndroidManifest.xml"))
        val application = document.getElementsByTagName("application").item(0) as Element
        assertEquals("false", application.getAttributeNS(androidNamespace, "allowBackup"))
        assertEquals("true", application.getAttributeNS(androidNamespace, "usesCleartextTraffic"))
        assertEquals("@string/app_name", application.getAttributeNS(androidNamespace, "label"))
        assertEquals(".service.LinkApp", application.getAttributeNS(androidNamespace, "name"))
        val expected = mapOf(
            "activity" to setOf(".ui.StatusActivity", ".device.power.TrampolineActivity"),
            "activity-alias" to emptySet(),
            "service" to setOf(".device.access.LinkAccessibilityService", ".service.ConnectionService", ".device.notify.LinkNotificationListener"),
            "receiver" to setOf(".service.BootReceiver"),
            "provider" to setOf("androidx.core.content.FileProvider"),
        )
        for ((tag, names) in expected) {
            val elements = document.getElementsByTagName(tag)
            val actual = (0 until elements.length).map { (elements.item(it) as Element).getAttributeNS(androidNamespace, "name") }
            assertEquals("Unexpected $tag components", names, actual.toSet())
            assertEquals("Duplicate $tag component", names.size, actual.size)
        }
        val permissionNames = setOf("INTERNET", "ACCESS_NETWORK_STATE", "FOREGROUND_SERVICE",
            "FOREGROUND_SERVICE_SPECIAL_USE", "POST_NOTIFICATIONS", "RECEIVE_BOOT_COMPLETED", "WAKE_LOCK",
            "TURN_SCREEN_ON", "REQUEST_IGNORE_BATTERY_OPTIMIZATIONS", "SEND_SMS", "CALL_PHONE", "READ_CONTACTS",
            "ACCESS_FINE_LOCATION", "ACCESS_COARSE_LOCATION", "ACCESS_BACKGROUND_LOCATION",
            "MANAGE_EXTERNAL_STORAGE", "RECORD_AUDIO", "MODIFY_AUDIO_SETTINGS", "READ_PHONE_STATE", "FOREGROUND_SERVICE_MICROPHONE",
            "WRITE_SECURE_SETTINGS")
            .map { "android.permission.$it" }.toSet()
        val permissions = document.getElementsByTagName("uses-permission")
        val actualPermissions = (0 until permissions.length).map {
            (permissions.item(it) as Element).getAttributeNS(androidNamespace, "name")
        }
        assertEquals(permissionNames, actualPermissions.toSet())
        assertEquals(21, actualPermissions.size)
    }

    private fun assertAllowed(location: String, value: String) {
        assertTrue("$location contains a banned label: $value", !banned.containsMatchIn(value))
    }

    private fun parse(file: File) = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
    }.newDocumentBuilder().parse(file)
    @Test fun pairingViewFilterIsExactAndStatusActivityUsesSingleTop() {
        val manifest = parse(File(main, "AndroidManifest.xml"))
        val activities = manifest.getElementsByTagName("activity")
        val status = (0 until activities.length).map { activities.item(it) as Element }
            .single { it.getAttributeNS(androidNamespace, "name") == ".ui.StatusActivity" }
        assertEquals("singleTop", status.getAttributeNS(androidNamespace, "launchMode"))
        val filters = status.getElementsByTagName("intent-filter")
        assertEquals(2, filters.length)
        val signatures = (0 until filters.length).map { index ->
            val filter = filters.item(index) as Element
            fun values(tag: String, attr: String): List<String> = filter.getElementsByTagName(tag).let { nodes ->
                (0 until nodes.length).map { (nodes.item(it) as Element).getAttributeNS(androidNamespace, attr) }
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
