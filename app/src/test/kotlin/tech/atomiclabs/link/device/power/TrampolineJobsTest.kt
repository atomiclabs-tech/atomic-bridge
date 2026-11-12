package tech.atomiclabs.link.device.power

import android.app.Activity
import android.app.KeyguardManager
import android.content.*
import io.mockk.*
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import tech.atomiclabs.link.core.fakes.FakeLogger
import tech.atomiclabs.link.core.port.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class TrampolineJobsTest {
    private val keyguard = mockk<KeyguardManager>(relaxed = true)
    private val clipboard = mockk<ClipboardManager>(relaxed = true)
    private val activity = mockk<Activity>(relaxed = true)
    private val logger = FakeLogger()
    private val results = mutableListOf<Pair<Long, PortResult<String?>>>()
    private fun TestScope.jobs() = TrampolineJobs(keyguard, clipboard, backgroundScope,
        { id, result -> results += id to result; true }, logger)
    private fun intent(job: String) = mockk<Intent> {
        every { hasExtra(TrampolineLauncher.EXTRA_ID) } returns true
        every { getLongExtra(TrampolineLauncher.EXTRA_ID, any()) } returns 27
        every { getStringExtra(TrampolineLauncher.EXTRA_JOB) } returns job
    }

    @Test fun clipboardWaitsForFocusThenCompletesRegisteredCallbackAndFinishesOnlyOnce() = runTest {
        val clip = mockk<ClipData>()
        val item = mockk<ClipData.Item>()
        every { clipboard.hasPrimaryClip() } returns true
        every { clipboard.primaryClip } returns clip
        every { clip.itemCount } returns 1
        every { clip.getItemAt(0) } returns item
        every { item.text } returns "private clipboard fixture"
        val jobs = jobs()
        jobs.onCreate(activity, intent(TrampolineLauncher.CLIPBOARD))
        verify { activity.setShowWhenLocked(true); activity.setTurnScreenOn(true) }
        verify(exactly = 0) { clipboard.primaryClip; activity.finish() }
        jobs.onWindowFocusChanged(activity, false)
        assertTrue(results.isEmpty())
        jobs.onWindowFocusChanged(activity, true)
        assertEquals(listOf(27L to PortResult.Success("private clipboard fixture")), results)
        jobs.onWindowFocusChanged(activity, true)
        jobs.onDestroyed(activity)
        advanceTimeBy(3_000); runCurrent()
        verify(exactly = 1) { activity.finish() }
        assertEquals(1, results.size)
        assertTrue(logger.entries.isEmpty())
    }

    @Test fun emptyClipboardReturnsEmptyTextOnlyAfterFocusAndCompletesOnce() = runTest {
        every { clipboard.hasPrimaryClip() } returns false
        every { clipboard.primaryClip } returns null
        val jobs = jobs()
        jobs.onCreate(activity, intent(TrampolineLauncher.CLIPBOARD))
        jobs.onWindowFocusChanged(activity, false)
        verify(exactly = 0) { clipboard.hasPrimaryClip(); clipboard.primaryClip; activity.finish() }
        assertTrue(results.isEmpty())
        jobs.onWindowFocusChanged(activity, true)
        assertEquals(listOf(27L to PortResult.Success("")), results)
        jobs.onWindowFocusChanged(activity, true)
        jobs.onDestroyed(activity)
        advanceTimeBy(3_000); runCurrent()
        assertEquals(1, results.size)
        verify(exactly = 1) { clipboard.hasPrimaryClip(); clipboard.primaryClip; activity.finish() }
        assertTrue(logger.entries.isEmpty())
    }

    @Test fun refusedInconsistentAndMalformedClipboardReadsStayLoggedFailures() = runTest {
        val clip = mockk<ClipData>()
        val item = mockk<ClipData.Item>()
        val scenarios = listOf<() -> Unit>(
            { every { clipboard.hasPrimaryClip() } throws SecurityException("private clipboard fixture") },
            { every { clipboard.primaryClip } returns null },
            { every { clipboard.hasPrimaryClip() } returns false },
            { every { clip.itemCount } returns 0 },
            { every { item.text } returns null },
        )
        val jobs = jobs()
        for (scenario in scenarios) {
            every { clipboard.hasPrimaryClip() } returns true
            every { clipboard.primaryClip } returns clip
            every { clip.itemCount } returns 1
            every { clip.getItemAt(0) } returns item
            every { item.text } returns "private clipboard fixture"
            scenario()
            jobs.onCreate(activity, intent(TrampolineLauncher.CLIPBOARD))
            jobs.onWindowFocusChanged(activity, true)
        }
        advanceTimeBy(3_000); runCurrent()
        assertEquals(scenarios.size, results.size)
        assertTrue(results.all { (it.second as PortResult.Failure).failure.kind == FailureKind.UNAVAILABLE })
        assertEquals(scenarios.size, logger.entries.size)
        assertTrue(logger.entries.all { it.level == "error" && it.operation == "readClipboard" })
        assertFalse(logger.entries.any { it.cause?.stackTraceToString()?.contains("private clipboard fixture") == true })
        assertFalse(logger.entries.any { it.context.contains("private clipboard fixture") })
        verify(exactly = 5) { activity.finish() }
    }

    @Test fun unfocusedClipboardJobFinishesWithLoggedFailureAtThreeSeconds() = runTest {
        val jobs = jobs()
        jobs.onCreate(activity, intent(TrampolineLauncher.CLIPBOARD))
        runCurrent(); advanceTimeBy(2_999); runCurrent()
        assertTrue(results.isEmpty())
        advanceTimeBy(1); runCurrent()
        assertEquals(FailureKind.TIMED_OUT, (results.single().second as PortResult.Failure).failure.kind)
        verify(exactly = 1) { activity.finish() }
        assertEquals("error", logger.entries.single().level)
    }

    @Test fun wakeOnAlreadyUnlockedDeviceWaitsForFocusThenSucceedsWithoutDismissal() = runTest {
        every { keyguard.isKeyguardLocked } returns false
        val jobs = jobs()
        jobs.onCreate(activity, intent(TrampolineLauncher.DISMISS))
        verify(exactly = 0) { activity.finish(); keyguard.requestDismissKeyguard(any(), any()) }
        assertTrue(results.isEmpty())
        jobs.onWindowFocusChanged(activity, true)
        assertEquals(PortResult.Success<String?>(null), results.single().second)
        verify(exactly = 1) { activity.finish() }
        verify(exactly = 0) { keyguard.requestDismissKeyguard(any(), any()) }
    }

    @Test fun secureKeyguardFailsWithoutRequestingCredentialsAndNonSecureDismissalUsesCheckedCallback() = runTest {
        every { keyguard.isKeyguardLocked } returns true
        every { keyguard.isKeyguardSecure } returns true
        val jobs = jobs()
        jobs.onCreate(activity, intent(TrampolineLauncher.DISMISS))
        jobs.onWindowFocusChanged(activity, true)
        assertEquals(FailureKind.REJECTED, (results.single().second as PortResult.Failure).failure.kind)
        verify(exactly = 0) { keyguard.requestDismissKeyguard(any(), any()) }
        results.clear()
        every { keyguard.isKeyguardSecure } returns false
        val callback = slot<KeyguardManager.KeyguardDismissCallback>()
        every { keyguard.requestDismissKeyguard(activity, capture(callback)) } just Runs
        jobs.onCreate(activity, intent(TrampolineLauncher.DISMISS))
        jobs.onWindowFocusChanged(activity, true)
        assertTrue(results.isEmpty())
        callback.captured.onDismissSucceeded()
        assertEquals(PortResult.Success<String?>(null), results.single().second)
    }

    @Test fun callbackRefusalClipboardReadFailureAndEarlyDestructionAreNotHidden() = runTest {
        val jobs = jobs()
        every { keyguard.isKeyguardLocked } returns true
        val callback = slot<KeyguardManager.KeyguardDismissCallback>()
        every { keyguard.requestDismissKeyguard(activity, capture(callback)) } just Runs
        for (cancelled in listOf(true, false)) {
            jobs.onCreate(activity, intent(TrampolineLauncher.DISMISS))
            jobs.onWindowFocusChanged(activity, true)
            if (cancelled) callback.captured.onDismissCancelled() else callback.captured.onDismissError()
        }
        every { clipboard.primaryClip } throws IllegalStateException("private clipboard fixture")
        jobs.onCreate(activity, intent(TrampolineLauncher.CLIPBOARD))
        jobs.onWindowFocusChanged(activity, true)
        jobs.onCreate(activity, intent(TrampolineLauncher.CLIPBOARD))
        jobs.onDestroyed(activity)
        assertEquals(4, results.size)
        assertTrue(results.all { it.second is PortResult.Failure })
        assertFalse(logger.entries.any { it.cause?.stackTraceToString()?.contains("private clipboard fixture") == true })
    }

    @Test fun themeIsTransparentAndHasNoAnimationsOrDim() {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File("src/main/res/values/styles_trampoline.xml"))
        val style = document.getElementsByTagName("style").item(0) as org.w3c.dom.Element
        assertEquals("android:style/Theme.Translucent.NoTitleBar", style.getAttribute("parent"))
        val items = style.getElementsByTagName("item")
        val values = (0 until items.length).associate {
            val item = items.item(it) as org.w3c.dom.Element
            item.getAttribute("name") to item.textContent
        }
        assertEquals("@android:color/transparent", values["android:windowBackground"])
        assertEquals("@null", values["android:windowAnimationStyle"])
        assertEquals("false", values["android:backgroundDimEnabled"])
    }
}
