package tech.atomiclabs.link.core.screen

import tech.atomiclabs.link.core.fakes.FakeClock
import java.security.MessageDigest
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import tech.atomiclabs.link.core.port.Bounds

class ScreenHasherTest {
    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }

    @Test fun fingerprintMatchesUpstreamMeaningAndStandardSha256AcrossPaddingLengthsAndUnicode() = runTest {
        val hasher = ScreenHasher(ScreenTreeWalker(FakeClock(testScheduler)))
        assertEquals(sha256(""), hasher.hash(snapshot()).hash)
        for (text in listOf("hello", "界🙂", "x".repeat(27), "x".repeat(45), "x".repeat(1000), "x".repeat(2100))) {
            val raw = node(text)
            raw.properties = raw.properties.copy(className = "Text", viewId = "app:id/value", contentDescription = "label")
            assertEquals(sha256("Text|$text|label|1,2,30,40|app:id/value"), hasher.hash(snapshot(raw)).hash)
        }
    }

    @Test fun textChangesButFocusAndSelectionAndExcludedNodesDoNotChangeTheHash() = runTest {
        val root = node()
        val hasher = ScreenHasher(ScreenTreeWalker(FakeClock(testScheduler)))
        val original = hasher.hash(snapshot(root))
        assertEquals(original, hasher.hash(snapshot(root)))
        root.properties = root.properties.copy(focused = true, selected = true, enabled = false)
        root.nodes += node("invisible", bounds = Bounds(0, 0, 0, 0))
        assertEquals(original, hasher.hash(snapshot(root)))
        root.properties = root.properties.copy(text = "changed")
        assertNotEquals(original.hash, hasher.hash(snapshot(root)).hash)
    }

    @Test fun multiNodeSeparatorAndNodeCapMatchTheUpstreamFingerprint() = runTest {
        val one = node("one")
        val two = node("two", id = 8)
        val hasher = ScreenHasher(ScreenTreeWalker(FakeClock(testScheduler)))
        assertEquals(sha256("|one||1,2,30,40|\u001e|two||1,2,30,40|"), hasher.hash(snapshot(one, two)).hash)
        val root = node(null)
        repeat(600) { root.nodes += node("item") }
        assertEquals(512, hasher.hash(snapshot(root)).nodeCount)
        assertTrue(hasher.hash(snapshot(root)).truncated)
    }
}
