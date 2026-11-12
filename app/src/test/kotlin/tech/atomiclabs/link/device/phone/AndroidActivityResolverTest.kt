package tech.atomiclabs.link.device.phone

import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import io.mockk.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import tech.atomiclabs.link.core.fakes.FakeLogger

@Suppress("DEPRECATION")
class AndroidActivityResolverTest {
    private val packages = mockk<PackageManager>()
    private val logger = FakeLogger()
    private val resolver = AndroidActivityResolver({ packages }, logger)

    @Before fun prepare() {
        mockkConstructor(ComponentName::class, Intent::class)
        every { anyConstructed<Intent>().addCategory(Intent.CATEGORY_HOME) } answers { self as Intent }
        every { anyConstructed<Intent>().setPackage(any()) } answers { self as Intent }
    }
    @After fun cleanup() { unmockkConstructor(ComponentName::class, Intent::class) }

    @Test fun foundActivityUsesTheExactPackageClassAndFlags() {
        every { constructedWith<ComponentName>(EqMatcher("example.app"), EqMatcher("example.app.Screen")).packageName } returns "example.app"
        every { constructedWith<ComponentName>(EqMatcher("example.app"), EqMatcher("example.app.Screen")).className } returns "example.app.Screen"
        val component = slot<ComponentName>()
        every { packages.getActivityInfo(capture(component), 0) } returns ActivityInfo()
        assertTrue(resolver.isActivity("example.app", "example.app.Screen"))
        assertEquals("example.app", component.captured.packageName)
        assertEquals("example.app.Screen", component.captured.className)
        verify(exactly = 1) { packages.getActivityInfo(any<ComponentName>(), 0) }
        assertTrue(logger.entries.isEmpty())
    }

    @Test fun missingActivityReturnsFalseAndLogsOnlyDebugWithoutExceptionContent() {
        every { packages.getActivityInfo(any<ComponentName>(), 0) } throws PackageManager.NameNotFoundException("private-class")
        assertFalse(resolver.isActivity("example.app", "android.widget.FrameLayout"))
        val entry = logger.entries.single()
        assertEquals("debug", entry.level)
        assertEquals("AndroidActivityResolver", entry.component)
        assertEquals("isActivity", entry.operation)
        assertEquals("package=example.app state=not_activity", entry.context)
        assertNull(entry.cause)
    }

    @Test fun relativeActivityNamesAreExpandedBeforeValidation() {
        every { constructedWith<ComponentName>(EqMatcher("example.app"), EqMatcher("example.app.HomeActivity")).className } returns "example.app.HomeActivity"
        every { constructedWith<ComponentName>(EqMatcher("example.app"), EqMatcher(".HomeActivity")).className } returns ".HomeActivity"
        every { packages.getActivityInfo(any<ComponentName>(), 0) } answers {
            if (firstArg<ComponentName>().className == "example.app.HomeActivity") ActivityInfo()
            else throw PackageManager.NameNotFoundException()
        }
        assertTrue(resolver.isActivity("example.app", ".HomeActivity"))
        assertTrue(resolver.isActivity("example.app", "HomeActivity"))
    }

    @Test fun otherLookupFailureReturnsFalseAndLogsTheSanitizedCauseAndStack() {
        val cause = SecurityException("private-lookup", IllegalStateException("private-cause"))
        every { packages.getActivityInfo(any<ComponentName>(), 0) } throws cause
        assertFalse(resolver.isActivity("example.app", "example.app.Screen"))
        assertLoggedFailure(cause)
    }

    @Test fun unavailablePackageManagerReturnsFalseAndLogsTheCause() {
        val cause = IllegalStateException("private-manager")
        assertFalse(AndroidActivityResolver({ throw cause }, logger).isActivity("example.app", "example.app.Screen"))
        assertLoggedFailure(cause)
        verify { packages wasNot Called }
    }

    @Test fun relativeWindowEventResolvesCanonicalMetadataWithoutQueryingEntries() {
        every { packages.getActivityInfo(any<ComponentName>(), 0) } returns info(".ConversationActivity")
        assertEquals("example.app.ConversationActivity", resolver.resolveActivity("example.app", ".ConversationActivity"))
        verify(exactly = 0) { packages.resolveActivity(any<Intent>(), any<Int>()); packages.getLaunchIntentForPackage(any()) }
    }

    @Test fun absentHomeUsesAValidatedLaunchEntryWithoutInventingATrampolineDestination() {
        launchEntry("example.app.MainActivity")
        every { packages.getActivityInfo(any<ComponentName>(), 0) } returns info("example.app.MainActivity")
        assertEquals("example.app.MainActivity", resolver.resolveActivity("example.app"))
        verify(exactly = 1) { packages.resolveActivity(any<Intent>(), PackageManager.MATCH_DEFAULT_ONLY) }
        verify(exactly = 1) { packages.getLaunchIntentForPackage("example.app") }
        assertTrue(logger.entries.isEmpty())
    }

    @Test fun declaredAliasTargetIsValidatedBeforeReturningItsCanonicalClass() {
        val alias = "example.app.EntryAlias"
        val target = "example.app.ConversationActivity"
        every { constructedWith<ComponentName>(EqMatcher("example.app"), EqMatcher(alias)).className } returns alias
        every { constructedWith<ComponentName>(EqMatcher("example.app"), EqMatcher(target)).className } returns target
        every { packages.getActivityInfo(any<ComponentName>(), 0) } answers {
            when (firstArg<ComponentName>().className) {
                alias -> info(alias).apply { targetActivity = ".ConversationActivity" }
                target -> info(target)
                else -> throw AssertionError("Unexpected synthetic component")
            }
        }
        assertEquals(target, resolver.resolveActivity("example.app", alias))
        verify(exactly = 2) { packages.getActivityInfo(any<ComponentName>(), 0) }
    }

    @Test fun absentHomeAndLaunchEntryReturnNullWithAnExplicitDebugReason() {
        every { packages.resolveActivity(any<Intent>(), any<Int>()) } returns null
        every { packages.getLaunchIntentForPackage("example.app") } returns null
        assertNull(resolver.resolveActivity("example.app"))
        assertEquals("package=example.app state=no_entry", logger.entries.single().context)
        assertEquals("debug", logger.entries.single().level)
        verify(exactly = 0) { packages.getActivityInfo(any<ComponentName>(), any<Int>()) }
    }

    @Test fun homeQueryFailureDoesNotProceedToAnUnrelatedEntryAndPreservesSanitizedCause() {
        val cause = SecurityException("private-home", IllegalStateException("private-cause"))
        every { packages.resolveActivity(any<Intent>(), any<Int>()) } throws cause
        assertNull(resolver.resolveActivity("example.app"))
        assertLoggedFailure(cause, "resolveActivity")
        verify(exactly = 0) { packages.getLaunchIntentForPackage(any()); packages.getActivityInfo(any<ComponentName>(), any<Int>()) }
    }

    @Test fun launchQueryFailureIsLoggedInsteadOfReturningUnvalidatedSuccess() {
        val cause = IllegalStateException("private-launch")
        every { packages.resolveActivity(any<Intent>(), any<Int>()) } returns null
        every { packages.getLaunchIntentForPackage("example.app") } throws cause
        assertNull(resolver.resolveActivity("example.app"))
        assertLoggedFailure(cause, "resolveActivity")
    }

    @Test fun resolutionWithoutAPackageManagerLogsItsCause() {
        val cause = IllegalStateException("private-manager")
        assertNull(AndroidActivityResolver({ throw cause }, logger).resolveActivity("example.app"))
        assertLoggedFailure(cause, "resolveActivity")
        verify { packages wasNot Called }
    }

    @Test fun homeResultForAnotherPackageIsRejectedWithoutTryingAnotherEntry() {
        every { packages.resolveActivity(any<Intent>(), any<Int>()) } returns ResolveInfo().apply {
            activityInfo = info(".HomeActivity").apply { packageName = "example.other" }
        }
        assertNull(resolver.resolveActivity("example.app"))
        assertEquals("error", logger.entries.single().level)
        assertNotNull(logger.entries.single().cause)
        verify(exactly = 0) { packages.getLaunchIntentForPackage(any()); packages.getActivityInfo(any<ComponentName>(), any<Int>()) }
    }

    @Test fun launchEntryFromAnotherPackageAndMissingComponentAreRejected() {
        launchEntry("example.app.MainActivity", "example.other")
        assertNull(resolver.resolveActivity("example.app"))
        assertEquals("error", logger.entries.single().level)
        val launch = mockk<Intent>()
        every { launch.component } returns null
        every { packages.getLaunchIntentForPackage("example.app") } returns launch
        assertNull(resolver.resolveActivity("example.app"))
        assertEquals(2, logger.entries.count { it.level == "error" && it.cause != null })
        verify(exactly = 0) { packages.getActivityInfo(any<ComponentName>(), any<Int>()) }
    }

    @Test fun mismatchedMetadataAndMissingAliasTargetNeverReturnAnActivity() {
        every { packages.getActivityInfo(any<ComponentName>(), 0) } returns info(".OtherActivity")
        assertNull(resolver.resolveActivity("example.app", ".Screen"))
        assertEquals("error", logger.entries.single().level)
        every { packages.getActivityInfo(any<ComponentName>(), 0) } returns info(".EntryAlias").apply { targetActivity = ".Missing" } andThenThrows
            PackageManager.NameNotFoundException("private-target")
        assertNull(resolver.resolveActivity("example.app", ".EntryAlias"))
        assertEquals("debug", logger.entries.last().level)
        assertEquals("package=example.app state=not_activity", logger.entries.last().context)
        assertFalse(logger.entries.any { it.context.contains("private-") || it.cause?.stackTraceToString()?.contains("private-") == true })
    }

    private fun info(name: String) = ActivityInfo().apply { packageName = "example.app"; this.name = name }

    private fun launchEntry(name: String, packageName: String = "example.app") {
        every { packages.resolveActivity(any<Intent>(), any<Int>()) } returns null
        val launch = mockk<Intent>()
        val component = mockk<ComponentName>()
        every { component.packageName } returns packageName
        every { component.className } returns name
        every { launch.component } returns component
        every { packages.getLaunchIntentForPackage("example.app") } returns launch
    }

    private fun assertLoggedFailure(cause: Throwable, operation: String = "isActivity") {
        val entry = logger.entries.single()
        assertEquals("error", entry.level)
        assertEquals("AndroidActivityResolver", entry.component)
        assertEquals(operation, entry.operation)
        assertEquals("package=example.app state=resolve_failed", entry.context)
        assertArrayEquals(cause.stackTrace, entry.cause!!.stackTrace)
        assertEquals(cause.cause != null, entry.cause.cause != null)
        assertFalse(entry.cause.stackTraceToString().contains("private-"))
    }
}
