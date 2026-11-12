package tech.atomiclabs.link.service

import android.app.Notification
import android.app.Service
import android.content.pm.ServiceInfo
import android.os.PowerManager
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import tech.atomiclabs.link.core.audio.ProbeRunner
import tech.atomiclabs.link.core.fakes.*
import tech.atomiclabs.link.core.port.*

@OptIn(ExperimentalCoroutinesApi::class)
class MicrophoneForegroundTest {
    private class Fixture(scheduler: TestCoroutineScheduler) {
        val logger = FakeLogger()
        val service = mockk<Service>(relaxed = true)
        val notification = mockk<ServiceNotification>()
        val built = mockk<Notification>()
        var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        val attempts = mutableListOf<Int>()
        val foreground = MicrophoneForeground(notification, logger, StandardTestDispatcher(scheduler))
        init {
            every { notification.build() } returns built
            every { service.foregroundServiceType } answers { type }
            every { service.startForeground(ServiceNotification.ID, built, any()) } answers {
                type = thirdArg(); attempts += type
            }
        }
    }

    @Test fun promotesOnlyTheExistingServiceAndRestoresItsActualPriorTypeWithoutStoppingIt() = runTest {
        val f = Fixture(testScheduler)
        f.foreground.available(f.service)
        val started = f.foreground.startMicrophone() as PortResult.Success
        assertTrue(started.value.started)
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE, f.type)
        assertEquals(PortResult.Success(Unit), f.foreground.stopMicrophone())
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE, f.type)
        assertEquals(2, f.attempts.size)
        assertEquals(PortResult.Success(Unit), f.foreground.stopMicrophone())
        verify(exactly = 0) { f.service.stopSelf(); f.service.stopForeground(any<Int>()) }
        assertTrue(f.logger.entries.isEmpty())
    }

    @Test fun unavailableUnpromotedAndDestroyedServicesCannotFabricateAStartedOutcome() = runTest {
        val f = Fixture(testScheduler)
        assertEquals(FailureKind.UNAVAILABLE, (f.foreground.startMicrophone() as PortResult.Failure).failure.kind)
        f.foreground.available(f.service)
        f.type = ServiceInfo.FOREGROUND_SERVICE_TYPE_NONE
        assertTrue(f.foreground.startMicrophone() is PortResult.Failure)
        f.foreground.available(null)
        assertTrue(f.foreground.startMicrophone() is PortResult.Failure)
        assertTrue(f.attempts.isEmpty())
    }

    @Test fun refusedPromotionKeepsTheRealFailureAndProbeStillMeasuresOtherOutcomesWithoutRetry() = runTest {
        val f = Fixture(testScheduler)
        val refusal = SecurityException("private-native-promotion-refusal")
        f.foreground.available(f.service)
        every { f.service.startForeground(any(), any(), any()) } throws refusal
        val fake = FakeAudioPorts()
        val runner = ProbeRunner(fake, fake, fake, fake, f.foreground, FakeClock(testScheduler), f.logger, Dispatchers.Unconfined)
        val report = (runner.run(AudioProbePlan(speaker = ProbeSpeaker.ON, sources = listOf(AudioCaptureSource.MIC))) as PortResult.Success).value
        assertFalse(report.foreground.started)
        assertEquals(refusal.message, report.foreground.error)
        assertEquals(1, fake.captures.size); assertEquals(2, fake.plays.size)
        verify(exactly = 1) { f.service.startForeground(any(), any(), any()) }
        verify(exactly = 0) { f.service.stopSelf(); f.service.stopForeground(any<Int>()) }
        assertTrue(f.logger.entries.any { it.level == "error" && it.cause != null })
        assertFalse(f.logger.entries.joinToString { it.cause?.stackTraceToString().orEmpty() }.contains(refusal.message!!))
    }

    @Test fun aPartiallyAppliedFailedPromotionIsRestoredAndAnUnappliedTypeIsNeverReportedStarted() = runTest {
        val f = Fixture(testScheduler)
        f.foreground.available(f.service)
        val refusal = SecurityException("promotion failed after application")
        every { f.service.startForeground(any(), any(), any()) } answers {
            f.type = thirdArg()
            if (f.type and ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE != 0) throw refusal
        }
        val startFailure = (f.foreground.startMicrophone() as PortResult.Failure).failure.cause
        assertTrue(generateSequence(startFailure) { it.cause }.any { it === refusal })
        assertEquals(PortResult.Success(Unit), f.foreground.stopMicrophone())
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE, f.type)
        every { f.service.startForeground(any(), any(), any()) } just Runs
        assertTrue(f.foreground.startMicrophone() is PortResult.Failure)
        assertEquals(PortResult.Success(Unit), f.foreground.stopMicrophone())
    }

    @Test fun failedRestorationIsCheckedLoggedAndNotReportedAsSuccessfulStop() = runTest {
        val f = Fixture(testScheduler)
        f.foreground.available(f.service)
        assertTrue(f.foreground.startMicrophone() is PortResult.Success)
        val cause = IllegalStateException("private-restore-refusal")
        every { f.service.startForeground(any(), any(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) } throws cause
        val stopFailure = (f.foreground.stopMicrophone() as PortResult.Failure).failure.cause
        assertTrue(generateSequence(stopFailure) { it.cause }.any { it === cause })
        assertEquals("stop", f.logger.entries.single().operation)
        assertFalse(f.logger.entries.single().cause!!.stackTraceToString().contains(cause.message!!))
        verify(exactly = 1) { f.service.startForeground(any(), any(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) }
    }

    @Test fun actualServiceLifetimePublishesAvailabilityOnlyAfterPromotionAndClearsItBeforeRelease() = runTest {
        val f = Fixture(testScheduler)
        val power = mockk<PowerManager>()
        val wake = mockk<PowerManager.WakeLock>(relaxed = true)
        var held = false
        every { power.newWakeLock(any(), any()) } returns wake
        every { wake.acquire() } answers { held = true }
        every { wake.release() } answers { held = false }
        every { wake.isHeld } answers { held }
        val lifetime = ConnectionServiceLifetime(f.notification, power, f.logger, f.foreground::available) {}
        lifetime.created(f.service)
        assertTrue(f.foreground.startMicrophone() is PortResult.Success)
        lifetime.destroyed()
        assertFalse(held)
        assertTrue(f.foreground.startMicrophone() is PortResult.Failure)
        assertEquals(PortResult.Success(Unit), f.foreground.stopMicrophone())
    }
}
