package tech.atomiclabs.link.ui

import android.Manifest
import android.app.NotificationManager
import android.content.ComponentName
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.os.PowerManager
import android.os.Environment
import android.provider.Settings
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import tech.atomiclabs.link.core.fakes.FakeLogger
import tech.atomiclabs.link.core.fakes.FakeNotificationSource
import tech.atomiclabs.link.core.port.*

class AndroidPermissionStateTest {
    private val context = mockk<Context>()
    private val resolver = mockk<ContentResolver>()
    private val packages = mockk<PackageManager>()
    private val power = mockk<PowerManager>()
    private val notifications = mockk<NotificationManager>()
    private val component = mockk<ComponentName>()
    private val logger = FakeLogger()
    private val notificationSource = FakeNotificationSource().apply { enabled = PortResult.Success(true) }
    private val adapter = AndroidPermissionState(context, component, logger, notificationSource)

    @Before fun setUp() {
        mockkStatic(Settings.Secure::class, ComponentName::class, Environment::class)
        every { Environment.isExternalStorageManager() } returns true
        every { context.packageName } returns "fixture.app"
        every { context.contentResolver } returns resolver
        every { context.packageManager } returns packages
        every { context.getSystemService(PowerManager::class.java) } returns power
        every { context.getSystemService(NotificationManager::class.java) } returns notifications
        every { context.checkSelfPermission(any()) } returns PackageManager.PERMISSION_GRANTED
        every { power.isIgnoringBatteryOptimizations("fixture.app") } returns true
        every { notifications.areNotificationsEnabled() } returns true
        every { packages.isAutoRevokeWhitelisted } returns true
        every { Settings.Secure.getString(resolver, Settings.Secure.ACCESSIBILITY_ENABLED) } returns "1"
        every { Settings.Secure.getString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) } returns "fixture.component"
        every { ComponentName.unflattenFromString("fixture.component") } returns component
    }

    @After fun tearDown() { unmockkStatic(Settings.Secure::class, ComponentName::class, Environment::class) }

    @Test fun readsTheSpecificServiceAndAllThirteenApprovedGrants() = runTest {
        val state = (adapter.read() as PortResult.Success).value
        assertEquals(PermissionSnapshot(true, true, true, true, true, true, true, true, true,
            allFiles = true, microphone = true, phoneState = true, notificationAccess = true), state)
        verify { context.checkSelfPermission(Manifest.permission.SEND_SMS) }
        verify { context.checkSelfPermission(Manifest.permission.CALL_PHONE) }
        verify { context.checkSelfPermission(Manifest.permission.READ_CONTACTS) }
        verify { context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) }
        verify { context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) }
    }

    @Test fun disabledServiceAndDeniedPermissionsAreActualMissingStates() = runTest {
        every { Settings.Secure.getString(resolver, Settings.Secure.ACCESSIBILITY_ENABLED) } returns "0"
        every { context.checkSelfPermission(any()) } returns PackageManager.PERMISSION_DENIED
        every { power.isIgnoringBatteryOptimizations(any()) } returns false
        every { packages.isAutoRevokeWhitelisted } returns false
        every { Environment.isExternalStorageManager() } returns false
        notificationSource.enabled = PortResult.Success(false)
        val state = (adapter.read() as PortResult.Success).value
        assertEquals(PermissionSnapshot(false, false, false, false, false, false, false, false, false), state)
    }

    @Test fun coarseLocationIsUsableAndDisabledAppNotificationsRemainMissing() = runTest {
        every { context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) } returns PackageManager.PERMISSION_DENIED
        every { notifications.areNotificationsEnabled() } returns false
        val state = (adapter.read() as PortResult.Success).value
        assertTrue(state.location)
        assertFalse(state.notifications)
    }

    @Test fun anotherEnabledAccessibilityServiceDoesNotGrantThisOne() = runTest {
        every { ComponentName.unflattenFromString("fixture.component") } returns mockk()
        assertFalse((adapter.read() as PortResult.Success).value.accessibility)
    }

    @Test fun platformFailurePreservesCauseAndCannotBecomeAFalseSnapshot() = runTest {
        val cause = IllegalStateException("private-platform-fixture")
        every { power.isIgnoringBatteryOptimizations(any()) } throws cause
        val result = adapter.read() as PortResult.Failure
        assertTrue(generateSequence(result.failure.cause) { it.cause }.any { it === cause })
        assertEquals(FailureKind.PLATFORM, result.failure.kind)
        assertFalse(logger.entries.single().cause!!.stackTraceToString().contains("private-platform"))
    }

    @Test fun malformedAccessibilityStateIsAnExplicitFailure() = runTest {
        every { Settings.Secure.getString(resolver, Settings.Secure.ACCESSIBILITY_ENABLED) } returns "invalid"
        assertTrue(adapter.read() is PortResult.Failure)
        assertEquals("read", logger.entries.single().operation)
    }

    @Test fun securityFailureRetainsItsTypedFailureKind() = runTest {
        every { context.checkSelfPermission(any()) } throws SecurityException("private-permission-fixture")
        assertEquals(FailureKind.PERMISSION_DENIED, (adapter.read() as PortResult.Failure).failure.kind)
        assertFalse(logger.entries.single().cause!!.stackTraceToString().contains("private-permission"))
    }
    @Test fun notificationQueryFailurePreservesTypedCauseAndCannotManufactureDeniedSnapshot() = runTest {
        val cause = SecurityException("private-notification-query")
        val failure = PortFailure(FailureKind.PERMISSION_DENIED, cause)
        notificationSource.enabled = PortResult.Failure(failure)
        val result = adapter.read() as PortResult.Failure
        assertSame(failure, result.failure)
        assertNull(result.failure.permission)
        assertEquals("notificationAccess", logger.entries.single().operation)
        assertFalse(logger.entries.single().cause!!.stackTraceToString().contains("private-notification-query"))
    }
}
