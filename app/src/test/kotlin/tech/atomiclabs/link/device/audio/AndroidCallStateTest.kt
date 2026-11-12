package tech.atomiclabs.link.device.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.TelephonyManager
import io.mockk.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import tech.atomiclabs.link.core.fakes.FakeLogger
import tech.atomiclabs.link.core.port.*

class AndroidCallStateTest {
    @Test fun readsAllThreeRealStatesWithoutInventingAnUnknownState() = runTest {
        val context = mockk<Context>(); val manager = mockk<TelephonyManager>(); val logger = FakeLogger()
        every { context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) } returns PackageManager.PERMISSION_GRANTED
        every { context.getSystemService(TelephonyManager::class.java) } returns manager
        val adapter = AndroidCallState(context, logger, StandardTestDispatcher(testScheduler))
        for ((platform, expected) in listOf(TelephonyManager.CALL_STATE_IDLE to CallState.IDLE,
            TelephonyManager.CALL_STATE_RINGING to CallState.RINGING, TelephonyManager.CALL_STATE_OFFHOOK to CallState.OFFHOOK)) {
            every { manager.callState } returns platform
            assertEquals(PortResult.Success(expected), adapter.read())
        }
        every { manager.callState } returns 99
        assertEquals(FailureKind.PLATFORM, (adapter.read() as PortResult.Failure).failure.kind)
        assertEquals("error", logger.entries.single().level)
    }

    @Test fun deniedPermissionUnavailableServiceAndPlatformSecurityFailureAreExplicitAndPrivate() = runTest {
        val context = mockk<Context>(); val logger = FakeLogger()
        val adapter = AndroidCallState(context, logger, StandardTestDispatcher(testScheduler))
        every { context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) } returns PackageManager.PERMISSION_DENIED
        assertEquals(Manifest.permission.READ_PHONE_STATE, (adapter.read() as PortResult.Failure).failure.permission)
        verify(exactly = 0) { context.getSystemService(TelephonyManager::class.java) }
        every { context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) } returns PackageManager.PERMISSION_GRANTED
        every { context.getSystemService(TelephonyManager::class.java) } returns null
        assertEquals(FailureKind.PLATFORM, (adapter.read() as PortResult.Failure).failure.kind)
        every { context.getSystemService(TelephonyManager::class.java) } throws SecurityException("private call detail")
        val denied = adapter.read() as PortResult.Failure
        assertEquals(FailureKind.PERMISSION_DENIED, denied.failure.kind)
        assertEquals("private call detail", denied.failure.cause.message)
        assertTrue(logger.entries.all { !it.cause!!.stackTraceToString().contains("private call detail") })
    }
}
