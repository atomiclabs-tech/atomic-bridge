package tech.atomiclabs.link.service

import android.app.Service
import android.app.Notification
import android.content.Context
import android.content.ComponentName
import android.content.Intent
import android.os.PowerManager
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.boolean
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import tech.atomiclabs.link.core.fakes.FakeLogger
import tech.atomiclabs.link.core.fakes.FakeClock
import tech.atomiclabs.link.core.port.ConnectionPhase
import tech.atomiclabs.link.core.protocol.BridgeCommand

@OptIn(ExperimentalCoroutinesApi::class)
class ServiceStarterTest {
    @Before fun setup() { mockkConstructor(Intent::class) }
    @After fun cleanup() { unmockkConstructor(Intent::class) }

    @Test fun foregroundRefusalStillStartsRuntimeAndEveryLaterTriggerRetries() {
        val context = mockk<Context>()
        val logger = FakeLogger()
        var starts = 0
        every { context.startForegroundService(any()) } throws SecurityException("private-start")
        val starter = ServiceStarter(context, logger) { starts++ }
        starter.start()
        assertEquals(1, starts)
        assertEquals("startForegroundService", logger.entries.single().operation)
        assertFalse(logger.entries.single().cause!!.stackTraceToString().contains("private-start"))
        every { context.startForegroundService(any()) } returns mockk<ComponentName>()
        starter.start()
        assertEquals(2, starts)
        verify(exactly = 2) { context.startForegroundService(any()) }
    }

    @Test fun aNullForegroundResultIsAReportedFailureAndDoesNotSkipRuntime() {
        val context = mockk<Context>()
        every { context.startForegroundService(any()) } returns null
        val logger = FakeLogger()
        var started = false
        ServiceStarter(context, logger) { started = true }.start()
        assertTrue(started); assertEquals("error", logger.entries.single().level)
    }

    @Test fun nativePromotionRefusalEndsItsStartAndRuntimeSurvivesForALaterServiceRetry() = runTest {
        val context = mockk<Context>()
        every { context.startForegroundService(any()) } returns mockk<ComponentName>()
        val refusedService = mockk<Service>(relaxed = true)
        val retryService = mockk<Service>(relaxed = true)
        val refusal = SecurityException("private-promotion-refusal")
        every { refusedService.startForeground(any(), any(), any()) } throws refusal
        val notification = mockk<ServiceNotification>()
        every { notification.build() } returns mockk<Notification>()
        val power = mockk<PowerManager>()
        val lock = mockk<PowerManager.WakeLock>(relaxed = true)
        every { power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AtomicLink:running") } returns lock
        var held = false
        every { lock.acquire() } answers { held = true }
        every { lock.release() } answers { held = false }
        every { lock.isHeld } answers { held }
        val logger = FakeLogger()
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        var installed = 0
        var terminated = false
        val runtime = LinkRuntime(logger, runtimeInputs(FakeClock(testScheduler)), scope,
            installFailureHandler = { installed++ }, previousFailureHandler = null,
            terminateProcess = { terminated = true })
        val starter = ServiceStarter(context, logger, runtime::start)
        val availability = mutableListOf<Service?>()
        val lifetime = ConnectionServiceLifetime(notification, power, logger, { availability += it }, runtime::start)
        try {
            starter.start()
            runCurrent()
            // Invoke the actual promotion owner outside ServiceStarter's scheduling-error catch.
            lifetime.created(refusedService)
            lifetime.destroyed()
            assertNull(availability.last())
            verify(exactly = 1) { refusedService.stopSelf() }
            verify(exactly = 0) { power.newWakeLock(any(), any()) }
            assertTrue(scope.isActive)
            assertFalse(terminated)
            assertEquals(ConnectionPhase.UNPAIRED, runtime.supervisor.states.value.phase)
            val response = runtime.router.dispatch(BridgeCommand("after-refusal", "GET", "/ping"))
            assertTrue(response.result["success"]!!.jsonPrimitive.boolean)
            val logged = logger.entries.single { it.operation == "startForeground" }
            assertEquals("error", logged.level)
            assertTrue(logged.cause!!.stackTrace.contentEquals(refusal.stackTrace))
            assertFalse(logged.cause.stackTraceToString().contains("private-promotion-refusal"))
            starter.start()
            lifetime.created(retryService)
            assertSame(retryService, availability.last())
            assertEquals(1, installed)
            assertTrue(held)
            verify(exactly = 2) { context.startForegroundService(any()) }
            verify(exactly = 1) { retryService.startForeground(any(), any(), any()); lock.acquire() }
            verify(exactly = 0) { retryService.stopSelf(); lock.acquire(any<Long>()) }
        } finally {
            lifetime.destroyed()
            runtime.shutdown()
        }
        assertFalse(held)
        verify(exactly = 1) { lock.release() }
    }

    @Test fun serviceOwnsPermanentPartialLockAndReleasesItWhenDestroyed() {
        val service = mockk<Service>(relaxed = true)
        val notification = mockk<ServiceNotification>()
        val built = mockk<Notification>()
        every { notification.build() } returns built
        val power = mockk<PowerManager>()
        val lock = mockk<PowerManager.WakeLock>(relaxed = true)
        every { power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AtomicLink:running") } returns lock
        var held = false
        every { lock.acquire() } answers { held = true }
        every { lock.release() } answers { held = false }
        every { lock.isHeld } answers { held }
        var starts = 0
        val availability = mutableListOf<Service?>()
        val lifetime = ConnectionServiceLifetime(notification, power, FakeLogger(), { availability += it }) { starts++ }
        lifetime.created(service); lifetime.destroyed(); lifetime.destroyed()
        assertEquals(1, starts); assertFalse(held)
        assertEquals(listOf(service, null, null), availability)
        verify(exactly = 1) { lock.acquire(); lock.release() }
        verify(exactly = 0) { lock.acquire(any<Long>()) }
        verify { lock.setReferenceCounted(false) }
        verify { service.startForeground(1, built, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) }
    }

    @Test fun wakeAcquisitionAndReleaseFailuresAreLoggedAndRuntimeStillStarts() {
        val service = mockk<Service>(relaxed = true)
        val notification = mockk<ServiceNotification>()
        every { notification.build() } returns mockk()
        val power = mockk<PowerManager>()
        val lock = mockk<PowerManager.WakeLock>(relaxed = true)
        every { power.newWakeLock(any(), any()) } returns lock
        every { lock.isHeld } returns false
        val logger = FakeLogger()
        var starts = 0
        val lifetime = ConnectionServiceLifetime(notification, power, logger, {}) { starts++ }
        assertThrows(IllegalStateException::class.java) { lifetime.created(service) }
        assertEquals(1, starts)
        every { lock.isHeld } returns true
        lifetime.created(service)
        every { lock.release() } throws SecurityException("private-release")
        assertThrows(IllegalStateException::class.java) { lifetime.destroyed() }
        assertEquals(2, logger.entries.count { it.level == "error" })
        assertFalse(logger.entries.any { it.cause!!.stackTraceToString().contains("private-release") })
    }

    @Test fun failedCleanupRetainsTheOriginalServiceFailureAndBothStacks() {
        val service = mockk<Service>(relaxed = true)
        val notification = mockk<ServiceNotification>()
        every { notification.build() } returns mockk()
        val power = mockk<PowerManager>()
        val lock = mockk<PowerManager.WakeLock>(relaxed = true)
        every { power.newWakeLock(any(), any()) } returns lock
        every { lock.isHeld } returnsMany listOf(false, true)
        val cleanup = SecurityException("private-cleanup")
        every { lock.release() } throws cleanup
        val logger = FakeLogger()
        val lifetime = ConnectionServiceLifetime(notification, power, logger, {}) {}
        val failure = assertThrows(IllegalStateException::class.java) { lifetime.created(service) }
        assertEquals("The connection wake lock was not acquired.", failure.cause!!.message)
        assertSame(cleanup, failure.cause!!.suppressed.single().cause)
        assertEquals(2, logger.entries.count { it.level == "error" })
    }
}
