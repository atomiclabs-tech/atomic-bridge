package tech.atomiclabs.link.ui

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
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
class AndroidSetupActionsTest {
    private val activity = mockk<Activity>(relaxed = true)
    private val packages = mockk<PackageManager>()
    private val component = mockk<ComponentName>()
    private val uri = mockk<Uri>()
    private val activityArgument = object : Matcher<Activity> {
        override fun match(arg: Activity?) = arg === activity
    }
    private val uriArgument = object : Matcher<Uri> {
        override fun match(arg: Uri?) = arg === uri
    }
    private val logger = FakeLogger()
    private val actions = AndroidSetupActions(activity, logger)
    private var requested = emptyArray<String>()
    private var requestCode = 0

    @Before fun setUp() {
        mockkConstructor(Intent::class)
        mockkStatic(Uri::class)
        every { activity.packageName } returns "fixture.app"
        every { activity.packageManager } returns packages
        every { Uri.fromParts("package", "fixture.app", null) } returns uri
        every { constructedWith<Intent>(EqMatcher(Settings.ACTION_ACCESSIBILITY_SETTINGS)).resolveActivity(packages) } returns component
        every { constructedWith<Intent>(EqMatcher(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)).resolveActivity(packages) } returns component
        every { constructedWith<Intent>(EqMatcher(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION), uriArgument).resolveActivity(packages) } returns component
        every { constructedWith<Intent>(EqMatcher(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS), uriArgument).resolveActivity(packages) } returns component
        every { constructedWith<Intent>(EqMatcher(Settings.ACTION_APPLICATION_DETAILS_SETTINGS), uriArgument).resolveActivity(packages) } returns component
        every { constructedWith<Intent>(EqMatcher(Settings.ACTION_APP_NOTIFICATION_SETTINGS)).resolveActivity(packages) } returns component
        every { constructedWith<Intent>(EqMatcher(Settings.ACTION_APP_NOTIFICATION_SETTINGS))
            .putExtra(Settings.EXTRA_APP_PACKAGE, "fixture.app") } answers { self as Intent }
        every { constructedWith<Intent>(activityArgument, EqMatcher(StatusActivity::class.java)).resolveActivity(packages) } returns component
        every { activity.requestPermissions(any(), any()) } answers {
            requested = firstArg(); requestCode = secondArg()
            actions.onRequestPermissionsResult(requestCode, requested, IntArray(requested.size) { PackageManager.PERMISSION_GRANTED })
        }
    }

    @After fun tearDown() { unmockkConstructor(Intent::class); unmockkStatic(Uri::class) }

    @Test fun buildsAccessibilityBatteryAndAppDetailsIntents() = runTest {
        assertTrue(actions.openPermissionSettings(Permission.ACCESSIBILITY) is PortResult.Success)
        assertTrue(actions.openPermissionSettings(Permission.BATTERY_EXEMPT) is PortResult.Success)
        assertTrue(actions.openPermissionSettings(Permission.UNUSED_APP_RESTRICTIONS_DISABLED) is PortResult.Success)
        assertTrue(actions.openPermissionSettings(Permission.BACKGROUND_LOCATION) is PortResult.Success)
        verify { constructedWith<Intent>(EqMatcher(Settings.ACTION_ACCESSIBILITY_SETTINGS)).resolveActivity(packages) }
        verify { constructedWith<Intent>(EqMatcher(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS), uriArgument).resolveActivity(packages) }
        verify(exactly = 2) { constructedWith<Intent>(EqMatcher(Settings.ACTION_APPLICATION_DETAILS_SETTINGS), uriArgument).resolveActivity(packages) }
        verify(exactly = 4) { activity.startActivity(any()) }
    }

    @Test fun notificationSettingsAndExplicitStatusIntentUseOnlyThisPackage() = runTest {
        assertTrue(actions.openPermissionSettings(Permission.NOTIFICATIONS) is PortResult.Success)
        verify { constructedWith<Intent>(EqMatcher(Settings.ACTION_APP_NOTIFICATION_SETTINGS))
            .putExtra(Settings.EXTRA_APP_PACKAGE, "fixture.app") }
        assertTrue(actions.openStatus() is PortResult.Success)
        verify { constructedWith<Intent>(activityArgument, EqMatcher(StatusActivity::class.java)).resolveActivity(packages) }
    }

    @Test fun appInfoUsesThisPackagesDetailsScreenWithoutChangingAccessibilityOrRequestingPermissions() = runTest {
        assertEquals(PortResult.Success(Unit), actions.openAppInfo())
        verify(exactly = 1) { Uri.fromParts("package", "fixture.app", null) }
        verify(exactly = 1) {
            constructedWith<Intent>(EqMatcher(Settings.ACTION_APPLICATION_DETAILS_SETTINGS), uriArgument)
                .resolveActivity(packages)
        }
        verify(exactly = 1) { activity.startActivity(any()) }
        verify(exactly = 0) {
            constructedWith<Intent>(EqMatcher(Settings.ACTION_ACCESSIBILITY_SETTINGS)).resolveActivity(packages)
            activity.requestPermissions(any(), any())
        }
        assertTrue(logger.entries.isEmpty())
    }

    @Test fun appInfoMissingHandlerAndNativeRefusalAreTypedAndLoggedSafelyWithCause() = runTest {
        every {
            constructedWith<Intent>(EqMatcher(Settings.ACTION_APPLICATION_DETAILS_SETTINGS), uriArgument)
                .resolveActivity(packages)
        } returns null
        val missing = actions.openAppInfo() as PortResult.Failure
        assertEquals(FailureKind.PLATFORM, missing.failure.kind)
        assertTrue(missing.failure.cause is IllegalStateException)
        verify(exactly = 0) { activity.startActivity(any()) }
        every {
            constructedWith<Intent>(EqMatcher(Settings.ACTION_APPLICATION_DETAILS_SETTINGS), uriArgument)
                .resolveActivity(packages)
        } returns component
        val cause = SecurityException("private-app-info-fixture")
        every { activity.startActivity(any()) } throws cause
        val refused = actions.openAppInfo() as PortResult.Failure
        assertEquals(FailureKind.PERMISSION_DENIED, refused.failure.kind)
        assertTrue(generateSequence(refused.failure.cause) { it.cause }.any { it === cause })
        verify(exactly = 1) { activity.startActivity(any()) }
        assertEquals(2, logger.entries.size)
        logger.entries.forEach {
            assertEquals("error", it.level)
            assertEquals("openAppInfo", it.operation)
            assertNotNull(it.cause)
            assertFalse(it.cause!!.stackTraceToString().contains("private-app-info-fixture"))
        }
        assertTrue(generateSequence(logger.entries.last().cause!!) { it.cause }
            .any { it.stackTrace.contentEquals(cause.stackTrace) })
    }

    @Test fun requestsExactlyTheApprovedRuntimePermissionsAndWaitsForResults() = runTest {
        val cases = mapOf(
            Permission.NOTIFICATIONS to arrayOf(Manifest.permission.POST_NOTIFICATIONS),
            Permission.SMS to arrayOf(Manifest.permission.SEND_SMS),
            Permission.CALL to arrayOf(Manifest.permission.CALL_PHONE),
            Permission.CONTACTS to arrayOf(Manifest.permission.READ_CONTACTS),
            Permission.LOCATION to arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
            Permission.MICROPHONE to arrayOf(Manifest.permission.RECORD_AUDIO),
            Permission.PHONE_STATE to arrayOf(Manifest.permission.READ_PHONE_STATE),
        )
        for ((permission, names) in cases) {
            assertTrue(actions.requestPermission(permission) is PortResult.Success)
            assertArrayEquals(names, requested)
        }
    }

    @Test fun deniedPermissionAndMalformedCallbacksReturnExplicitFailures() = runTest {
        every { activity.requestPermissions(any(), any()) } answers {
            val names = firstArg<Array<String>>()
            actions.onRequestPermissionsResult(secondArg(), names, IntArray(names.size) { PackageManager.PERMISSION_DENIED })
        }
        assertEquals(FailureKind.PERMISSION_DENIED, (actions.requestPermission(Permission.SMS) as PortResult.Failure).failure.kind)
        every { activity.requestPermissions(any(), any()) } answers {
            actions.onRequestPermissionsResult(secondArg(), emptyArray(), intArrayOf())
        }
        assertEquals(FailureKind.PLATFORM, (actions.requestPermission(Permission.SMS) as PortResult.Failure).failure.kind)
        assertEquals(2, logger.entries.size)
    }

    @Test fun coarseOnlyLocationResultIsSuccessful() = runTest {
        every { activity.requestPermissions(any(), any()) } answers {
            actions.onRequestPermissionsResult(secondArg(), firstArg(), intArrayOf(PackageManager.PERMISSION_DENIED, PackageManager.PERMISSION_GRANTED))
        }
        assertTrue(actions.requestPermission(Permission.LOCATION) is PortResult.Success)
    }
    @Test fun newSpecialAccessRowsOpenTheExactSettingsWithoutRuntimeRequests() = runTest {
        assertTrue(actions.openPermissionSettings(Permission.NOTIFICATION_ACCESS) is PortResult.Success)
        assertTrue(actions.openPermissionSettings(Permission.ALL_FILES) is PortResult.Success)
        verify { constructedWith<Intent>(EqMatcher(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)).resolveActivity(packages) }
        verify { constructedWith<Intent>(EqMatcher(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION), uriArgument).resolveActivity(packages) }
        verify(exactly = 2) { activity.startActivity(any()) }
        verify(exactly = 0) { activity.requestPermissions(any(), any()) }
    }

    @Test fun absentHandlerAndRefusedStartAreLoggedWithoutLeakingPlatformMessages() = runTest {
        every { constructedWith<Intent>(EqMatcher(Settings.ACTION_ACCESSIBILITY_SETTINGS)).resolveActivity(packages) } returns null
        assertTrue(actions.openPermissionSettings(Permission.ACCESSIBILITY) is PortResult.Failure)
        verify(exactly = 0) { activity.startActivity(any()) }
        every { constructedWith<Intent>(EqMatcher(Settings.ACTION_ACCESSIBILITY_SETTINGS)).resolveActivity(packages) } returns component
        val cause = SecurityException("private-intent-fixture")
        every { activity.startActivity(any()) } throws cause
        val result = actions.openStatus() as PortResult.Failure
        assertTrue(generateSequence(result.failure.cause) { it.cause }.any { it === cause })
        assertFalse(logger.entries.last().cause!!.stackTraceToString().contains("private-intent"))
    }

    @Test fun callbackTimeoutIsBoundedAndDoesNotRetry() = runTest {
        every { activity.requestPermissions(any(), any()) } just Runs
        val pending = async { actions.requestPermission(Permission.SMS) }
        advanceTimeBy(25_000); runCurrent()
        assertEquals(FailureKind.TIMED_OUT, (pending.await() as PortResult.Failure).failure.kind)
        verify(exactly = 1) { activity.requestPermissions(any(), any()) }
    }

    @Test fun concurrentPermissionRequestIsRejectedAndOriginalCallbackStillCompletes() = runTest {
        every { activity.requestPermissions(any(), any()) } answers { requested = firstArg(); requestCode = secondArg() }
        val first = async { actions.requestPermission(Permission.SMS) }
        runCurrent()
        assertEquals(FailureKind.REJECTED, (actions.requestPermission(Permission.CALL) as PortResult.Failure).failure.kind)
        actions.onRequestPermissionsResult(requestCode, requested, intArrayOf(PackageManager.PERMISSION_GRANTED))
        assertTrue(first.await() is PortResult.Success)
    }
}
