package tech.atomiclabs.link.device.phone

import android.content.*
import android.content.pm.*
import android.net.Uri
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import tech.atomiclabs.link.core.fakes.FakeLogger
import tech.atomiclabs.link.core.port.*

@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("DEPRECATION")
class AndroidAppControlTest {
    private val context = mockk<Context>()
    private val packages = mockk<PackageManager>()
    private val logger = FakeLogger()
    private val handler = mockk<ComponentName>()
    private val uri = mockk<Uri>()

    @Before fun prepare() {
        every { context.packageManager } returns packages
        every { context.startActivity(any()) } just Runs
        every { context.sendBroadcast(any()) } just Runs
        mockkConstructor(Intent::class)
        mockkStatic(Uri::class)
        every { anyConstructed<Intent>().addCategory(any()) } answers { self as Intent }
        every { anyConstructed<Intent>().addFlags(any()) } answers { self as Intent }
        every { anyConstructed<Intent>().setPackage(any()) } answers { self as Intent }
        every { anyConstructed<Intent>().setData(any()) } answers { self as Intent }
        every { anyConstructed<Intent>().setComponent(any()) } answers { self as Intent }
        every { constructedWith<Intent>(EqMatcher(Intent.ACTION_MAIN)).addCategory(Intent.CATEGORY_LAUNCHER) } answers { self as Intent }
        every { anyConstructed<Intent>().putExtra(any(), any<String>()) } answers { self as Intent }
        every { anyConstructed<Intent>().putExtra(any(), any<Long>()) } answers { self as Intent }
        every { anyConstructed<Intent>().putExtra(any(), any<Double>()) } answers { self as Intent }
        every { anyConstructed<Intent>().putExtra(any(), any<Boolean>()) } answers { self as Intent }
        every { anyConstructed<Intent>().resolveActivity(packages) } returns handler
        every { Uri.parse("sample:private-data") } returns uri
    }
    @After fun cleanup() { unmockkConstructor(Intent::class); unmockkStatic(Uri::class) }

    private fun adapter(test: TestScope) = AndroidAppControl(context, logger, StandardTestDispatcher(test.testScheduler))

    @Test fun launcherListingUsesMainLauncherAndDeduplicatesActualPackagesWithLabels() = runTest {
        val info = ApplicationInfo()
        fun resolved() = ResolveInfo().apply {
            activityInfo = ActivityInfo().apply { packageName = "fixture.app"; applicationInfo = info }
        }
        every { packages.queryIntentActivities(any<Intent>(), 0) } returns listOf(resolved(), resolved())
        every { packages.getApplicationLabel(info) } returns "Fixture label"
        assertEquals(PortResult.Success(listOf(LaunchableApp("fixture.app", "Fixture label"))), adapter(this).launchableApps())
        verify { constructedWith<Intent>(EqMatcher(Intent.ACTION_MAIN)).addCategory(Intent.CATEGORY_LAUNCHER) }
        verify(exactly = 1) { packages.getApplicationLabel(info) }
    }

    @Test fun unknownPackageAndLaunchRaceReturnNotFoundWhileSuccessUsesLauncherNewTask() = runTest {
        val reader = adapter(this)
        every { packages.getLaunchIntentForPackage("fixture.app") } returns null
        assertEquals(AppLaunchOutcome.NotFound, reader.launch("fixture.app"))
        val launch = mockk<Intent>()
        every { launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) } returns launch
        every { packages.getLaunchIntentForPackage("fixture.app") } returns launch
        assertEquals(AppLaunchOutcome.Launched, reader.launch("fixture.app"))
        verify(exactly = 1) { context.startActivity(launch) }
        every { context.startActivity(launch) } throws ActivityNotFoundException("private-race")
        assertEquals(AppLaunchOutcome.NotFound, reader.launch("fixture.app"))
        assertEquals(2, logger.entries.size)
        assertTrue(logger.entries.all { !it.cause!!.stackTraceToString().contains("private-race") })
    }

    @Test fun activityAndBroadcastBuildTypedExtrasDataPackageComponentAndCategory() = runTest {
        val reader = adapter(this)
        val spec = IntentSpec("fixture.ACTION", "sample:private-data", "fixture.app",
            IntentComponent("fixture.app", "fixture.app.Screen"),
            mapOf("text" to IntentExtra.Text("private-extra"), "integer" to IntentExtra.Integer(7),
                "decimal" to IntentExtra.Decimal(2.5), "flag" to IntentExtra.Flag(true)), "fixture.CATEGORY")
        assertEquals(ActivityOutcome.Started, reader.startActivity(spec))
        verify { anyConstructed<Intent>().data = uri }
        verify { anyConstructed<Intent>().setPackage("fixture.app") }
        verify { anyConstructed<Intent>().component = any<ComponentName>() }
        verify { anyConstructed<Intent>().addCategory("fixture.CATEGORY") }
        verify { anyConstructed<Intent>().putExtra("text", "private-extra") }
        verify { anyConstructed<Intent>().putExtra("integer", 7L) }
        verify { anyConstructed<Intent>().putExtra("decimal", 2.5) }
        verify { anyConstructed<Intent>().putExtra("flag", true) }
        verify(exactly = 1) { anyConstructed<Intent>().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        assertEquals(PortResult.Success(Unit), reader.sendBroadcast(spec.copy(component = null, category = null)))
        verify(exactly = 1) { context.sendBroadcast(any()) }
        verify(exactly = 1) { anyConstructed<Intent>().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        assertTrue(logger.entries.isEmpty())
    }

    @Test fun missingHandlerAndRefusedStartsBroadcastsAndListReadsCannotFakeSuccessOrLeak() = runTest {
        val reader = adapter(this)
        every { anyConstructed<Intent>().resolveActivity(packages) } returns null
        assertEquals(ActivityOutcome.NoHandler, reader.startActivity(IntentSpec("fixture.ACTION")))
        verify(exactly = 0) { context.startActivity(any()) }
        every { anyConstructed<Intent>().resolveActivity(packages) } returns handler
        val cause = SecurityException("private-extra")
        every { context.startActivity(any()) } throws cause
        val failed = reader.startActivity(IntentSpec("fixture.ACTION")) as ActivityOutcome.Failed
        assertEquals(FailureKind.PERMISSION_DENIED, failed.failure.kind)
        assertTrue(generateSequence(failed.failure.cause) { it.cause }.any { it === cause })
        every { context.sendBroadcast(any()) } throws cause
        assertEquals(FailureKind.PERMISSION_DENIED, (reader.sendBroadcast(IntentSpec("A")) as PortResult.Failure).failure.kind)
        every { packages.queryIntentActivities(any<Intent>(), 0) } throws cause
        assertTrue(reader.launchableApps() is PortResult.Failure)
        assertEquals(4, logger.entries.size)
        assertTrue(logger.entries.all { it.level == "error" && !it.cause!!.stackTraceToString().contains("private-extra") })
    }

    @Test fun malformedMetadataAndFailedLabelsDoNotBecomeEmptyListsOrInventLabels() = runTest {
        val reader = adapter(this)
        every { packages.queryIntentActivities(any<Intent>(), 0) } returns listOf(ResolveInfo())
        assertTrue(reader.launchableApps() is PortResult.Failure)
        val info = ApplicationInfo()
        every { packages.queryIntentActivities(any<Intent>(), 0) } returns listOf(ResolveInfo().apply {
            activityInfo = ActivityInfo().apply { packageName = "p"; applicationInfo = info }
        })
        every { packages.getApplicationLabel(info) } throws IllegalStateException("private-label")
        assertTrue(reader.launchableApps() is PortResult.Failure)
        assertEquals(2, logger.entries.size)
        assertTrue(logger.entries.all { !it.cause!!.stackTraceToString().contains("private-label") })
    }
}
