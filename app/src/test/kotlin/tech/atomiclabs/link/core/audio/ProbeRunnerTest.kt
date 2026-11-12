package tech.atomiclabs.link.core.audio

import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import tech.atomiclabs.link.core.fakes.*
import tech.atomiclabs.link.core.port.*

@OptIn(ExperimentalCoroutinesApi::class)
class ProbeRunnerTest {
    private class Fixture(scope: TestScope) {
        val clock = FakeClock(scope.testScheduler)
        val logger = FakeLogger()
        val fake = FakeAudioPorts()
        val operations = mutableListOf<String>()
        val handles = mutableListOf<FakeFileWriteHandle>()
        var discardFailure: IOException? = null
        var captureBlock: suspend (AudioCaptureRequest) -> PortResult<AudioCaptureMeasurement> = { request ->
            clock.sleep(request.durationMs)
            request.sample.target.close()
            PortResult.Success(AudioCaptureMeasurement(request.source, request.speaker, AudioInitialization.OK,
                32_000, 0.1, 0.2, false, request.sample.path))
        }
        var stopBlock: suspend () -> PortResult<Unit> = { fake.stopMicrophone() }
        val routing = object : AudioRouting {
            override suspend fun callState() = fake.callState()
            override suspend fun setSpeaker(enabled: Boolean) = PortResult.Success(listOf(SpeakerAttempt(enabled, "fixture", true)))
        }
        val samples = object : AudioSampleStore {
            override suspend fun clear(): PortResult<Unit> { operations += "clear"; return PortResult.Success(Unit) }
            override suspend fun create(source: AudioCaptureSource, speaker: Boolean): PortResult<AudioSampleFile> {
                operations += "create"
                val target = FakeFileWriteHandle(-1).also { it.discardFailure = discardFailure; handles += it }
                return PortResult.Success(AudioSampleFile("private-cache/audio-probe/${source.name}_$speaker.wav", target))
            }
        }
        val runner = ProbeRunner(AudioCapture { captureBlock(it) }, AudioPlayback {
            PortResult.Success(AudioPlaybackMeasurement(it.stream, it.speaker, true))
        }, routing, samples, object : AudioForeground {
            override suspend fun startMicrophone() = fake.startMicrophone()
            override suspend fun stopMicrophone() = stopBlock()
        }, clock, logger, StandardTestDispatcher(scope.testScheduler))
        suspend fun report(plan: AudioProbePlan) = (runner.run(plan) as PortResult.Success).value
    }

    @Test fun measuresEverySourceSpeakerAndInjectionAndClearsBeforeCreatingSamples() = runTest {
        val fixture = Fixture(this)
        val report = fixture.report(AudioProbePlan())
        assertEquals(CallState.OFFHOOK, report.callState); assertFalse(report.truncated)
        assertEquals(14, report.captures.size); assertEquals(2, report.playback.size)
        for (source in AudioCaptureSource.entries) for (speaker in listOf(false, true)) {
            val capture = report.captures.single { it.source == source && it.speaker == speaker }
            assertEquals(AudioInitialization.OK, capture.init); assertNull(capture.error)
            assertEquals(32_000, capture.bytes); assertEquals(0.1, capture.rms, 0.0)
            assertEquals(0.2, capture.peak, 0.0); assertFalse(capture.silent)
            assertTrue(capture.sample!!.contains("audio-probe/"))
        }
        assertEquals(AudioPlaybackStream.entries, report.playback.map { it.stream })
        assertTrue(report.playback.all { it.played && it.speaker })
        assertEquals(listOf(false, true, true), report.speakerAttempts.map { it.speaker })
        assertTrue(report.foreground.started)
        assertEquals("clear", fixture.operations.first())
        assertEquals(listOf("callState", "startMicrophone", "stopMicrophone"), fixture.fake.operations)
        assertTrue(fixture.handles.all { it.closed && !it.discarded })
        assertTrue(fixture.logger.entries.isEmpty())
    }

    @Test fun throwingAndFailedPortsBecomeExactFailedMeasurementsAndLaterSourcesRun() = runTest {
        val fixture = Fixture(this)
        fixture.captureBlock = { request -> when (request.source) {
            AudioCaptureSource.VOICE_CALL -> throw IllegalStateException("capture constructor denied")
            AudioCaptureSource.VOICE_DOWNLINK -> PortResult.Failure(PortFailure(FailureKind.PLATFORM, IllegalStateException("capture read failed")))
            else -> { request.sample.target.close(); PortResult.Success(AudioCaptureMeasurement(request.source, request.speaker,
                AudioInitialization.OK, 0, 0.0, 0.0, true, request.sample.path)) }
        } }
        val report = fixture.report(AudioProbePlan(speaker = ProbeSpeaker.OFF, inject = false,
            sources = listOf(AudioCaptureSource.VOICE_CALL, AudioCaptureSource.VOICE_DOWNLINK, AudioCaptureSource.MIC)))
        assertEquals(listOf("capture constructor denied", "capture read failed", null), report.captures.map { it.error })
        assertEquals(listOf(AudioInitialization.FAILED, AudioInitialization.FAILED, AudioInitialization.OK), report.captures.map { it.init })
        assertTrue(fixture.handles.take(2).all { it.discarded }); assertTrue(fixture.handles.last().closed)
        assertTrue(fixture.logger.entries.all { !it.cause!!.stackTraceToString().contains("capture constructor denied") })
        assertTrue(report.captures.take(2).all { it.sample == null && it.bytes == 0L })
    }

    @Test fun fullBudgetExceedsLegacy25SecondsAndReturnsPartialResultsWithin110SecondsIncludingStop() = runTest {
        val fixture = Fixture(this)
        fixture.stopBlock = { fixture.clock.sleep(500); PortResult.Success(Unit) }
        val report = fixture.report(AudioProbePlan(durationMs = 10_000))
        assertTrue(report.truncated); assertEquals(10, report.captures.size)
        assertTrue(fixture.clock.monotonicMs() in 100_000..110_000)
        assertTrue(report.playback.isEmpty()); assertTrue(fixture.handles.last().discarded)
    }

    @Test fun perRouteBudgetFailsOneRouteAndContinuesWithoutMarkingWholeProbeTruncated() = runTest {
        val fixture = Fixture(this)
        val normal = fixture.captureBlock
        fixture.captureBlock = { request ->
            if (request.source == AudioCaptureSource.VOICE_CALL) fixture.clock.sleep(30_000)
            normal(request)
        }
        val report = fixture.report(AudioProbePlan(speaker = ProbeSpeaker.OFF, inject = false,
            sources = listOf(AudioCaptureSource.VOICE_CALL, AudioCaptureSource.MIC)))
        assertEquals(AudioInitialization.FAILED, report.captures.first().init)
        assertTrue(report.captures.first().error!!.contains("budget"))
        assertEquals(AudioInitialization.OK, report.captures.last().init); assertFalse(report.truncated)
    }

    @Test fun stateReadFailureIsNotInventedAndForegroundStopFailureIsReported() = runTest {
        val fixture = Fixture(this)
        val cause = SecurityException("phone state denied")
        fixture.fake.failure = PortFailure(FailureKind.PERMISSION_DENIED, cause, "android.permission.READ_PHONE_STATE")
        assertSame(cause, (fixture.runner.run(AudioProbePlan()) as PortResult.Failure).failure.cause)
        assertTrue(fixture.handles.isEmpty())
        fixture.fake.failure = null
        fixture.fake.foreground = MicrophoneForegroundOutcome(false, "microphone foreground rejected")
        fixture.stopBlock = { PortResult.Failure(PortFailure(FailureKind.PLATFORM, IllegalStateException("stop rejected"))) }
        val report = fixture.report(AudioProbePlan(sources = emptyList(), inject = false))
        assertFalse(report.foreground.started)
        assertTrue(report.foreground.error!!.contains("microphone foreground rejected"))
        assertTrue(report.foreground.error!!.contains("stop rejected"))
    }

    @Test fun cancellationDiscardsCurrentSampleAndStopsForeground() = runTest {
        val fixture = Fixture(this)
        val job = launch { fixture.runner.run(AudioProbePlan()) }
        runCurrent(); advanceTimeBy(100); job.cancelAndJoin()
        assertTrue(fixture.handles.single().discarded)
        assertEquals("stopMicrophone", fixture.fake.operations.last())
    }

    @Test fun sampleCleanupFailureIsReportedAndLaterCaptureStillRuns() = runTest {
        val fixture = Fixture(this)
        fixture.discardFailure = IOException("sample discard rejected")
        val normal = fixture.captureBlock
        fixture.captureBlock = { request ->
            if (request.source == AudioCaptureSource.VOICE_CALL) throw IllegalStateException("capture rejected")
            normal(request)
        }
        val report = fixture.report(AudioProbePlan(speaker = ProbeSpeaker.OFF, inject = false,
            sources = listOf(AudioCaptureSource.VOICE_CALL, AudioCaptureSource.MIC)))
        assertEquals("capture rejected; Sample cleanup failed: sample discard rejected", report.captures.first().error)
        assertEquals(AudioInitialization.OK, report.captures.last().init)
        assertTrue(fixture.logger.entries.any { it.operation == "sample_discard" && it.level == "error" })
    }

    @Test fun truncationAllowsBoundedAdapterCleanupAndTimesOutForegroundStopWithinTotalBudget() = runTest {
        val fixture = Fixture(this)
        val normal = fixture.captureBlock
        fixture.captureBlock = { request ->
            try { normal(request) } finally {
                if (!currentCoroutineContext().isActive) withContext(NonCancellable) { fixture.clock.sleep(250) }
            }
        }
        fixture.stopBlock = { fixture.clock.sleep(5_000); PortResult.Success(Unit) }
        val report = fixture.report(AudioProbePlan(durationMs = 10_000))
        assertTrue(report.truncated); assertTrue(fixture.handles.last().discarded)
        assertTrue(report.foreground.error!!.contains("foreground_stop"))
        assertEquals(109_750L, fixture.clock.monotonicMs())
    }

    @Test fun queuedProbeDoesNotBeginWithoutEnoughTimeForItsCleanupReserve() = runTest {
        val fixture = Fixture(this)
        val normal = fixture.captureBlock
        fixture.captureBlock = { request ->
            try { normal(request) } finally {
                if (!currentCoroutineContext().isActive) withContext(NonCancellable) { fixture.clock.sleep(250) }
            }
        }
        fixture.stopBlock = { fixture.clock.sleep(5_000); PortResult.Success(Unit) }
        val active = async { fixture.runner.run(AudioProbePlan(durationMs = 10_000)) }
        runCurrent()
        val queued = async { fixture.runner.run(AudioProbePlan(durationMs = 10_000)) }
        assertTrue((active.await() as PortResult.Success).value.truncated)
        val rejected = queued.await() as PortResult.Failure
        assertEquals(FailureKind.TIMED_OUT, rejected.failure.kind)
        assertTrue(rejected.failure.cause.message!!.contains("queueing"))
        assertEquals(109_750L, fixture.clock.monotonicMs())
        assertEquals(1, fixture.operations.count { it == "clear" })
    }
}
