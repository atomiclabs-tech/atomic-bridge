package tech.atomiclabs.link.core.fakes

import tech.atomiclabs.link.core.port.*

class FakeAudioPorts : AudioProbe, AudioRouting, CallStateSource, AudioCapture, AudioPlayback, AudioSampleStore, AudioForeground {
    var failure: PortFailure? = null
    var state = CallState.OFFHOOK
    var report = AudioProbeReport(state, emptyList(), emptyList(), emptyList(), MicrophoneForegroundOutcome(true))
    var capture = AudioCaptureMeasurement(AudioCaptureSource.MIC, true, AudioInitialization.OK, 0, 0.0, 0.0, true)
    var playback = AudioPlaybackMeasurement(AudioPlaybackStream.USAGE_MEDIA, true, true)
    var speakerAttempts = listOf(SpeakerAttempt(true, "communication_device", true))
    var sample = AudioSampleFile("private-cache/fixture.wav", FakeFileWriteHandle(-1))
    var foreground = MicrophoneForegroundOutcome(true)
    val plans = mutableListOf<AudioProbePlan>()
    val captures = mutableListOf<AudioCaptureRequest>()
    val plays = mutableListOf<AudioPlaybackRequest>()
    val speakerRequests = mutableListOf<Boolean>()
    val sampleRequests = mutableListOf<Pair<AudioCaptureSource, Boolean>>()
    val operations = mutableListOf<String>()
    private fun <T> result(operation: String, value: T): PortResult<T> {
        operations += operation
        return failure?.let { PortResult.Failure(it) } ?: PortResult.Success(value)
    }
    override suspend fun run(plan: AudioProbePlan): PortResult<AudioProbeReport> {
        plans += plan
        return result("run", report)
    }
    override suspend fun setSpeaker(enabled: Boolean): PortResult<List<SpeakerAttempt>> {
        speakerRequests += enabled
        return result("setSpeaker", speakerAttempts)
    }
    override suspend fun callState() = result("callState", state)
    override suspend fun read() = result("read", state)
    override suspend fun capture(request: AudioCaptureRequest): PortResult<AudioCaptureMeasurement> {
        captures += request
        return result("capture", capture)
    }
    override suspend fun play(request: AudioPlaybackRequest): PortResult<AudioPlaybackMeasurement> {
        plays += request
        return result("play", playback)
    }
    override suspend fun clear() = result("clear", Unit)
    override suspend fun create(source: AudioCaptureSource, speaker: Boolean): PortResult<AudioSampleFile> {
        sampleRequests += source to speaker
        return result("create", sample)
    }
    override suspend fun startMicrophone() = result("startMicrophone", foreground)
    override suspend fun stopMicrophone() = result("stopMicrophone", Unit)
}
