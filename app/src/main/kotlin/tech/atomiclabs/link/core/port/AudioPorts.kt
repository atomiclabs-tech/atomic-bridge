// forge-allow: no-test declarations only
package tech.atomiclabs.link.core.port

/** Probe execution is bounded by plan budgets; a budget expiry returns measured partial results, marked truncated. */
fun interface AudioProbe { suspend fun run(plan: AudioProbePlan): PortResult<AudioProbeReport> }

/** Reports every attempted speaker method, including a permitted visible Speaker-control tap. */
interface AudioRouting {
    suspend fun setSpeaker(enabled: Boolean): PortResult<List<SpeakerAttempt>>
    suspend fun callState(): PortResult<CallState>
}

fun interface CallStateSource { suspend fun read(): PortResult<CallState> }

/** Captures 16 kHz mono PCM16 and measures it. Closes the supplied WAV handle; discards it on failure/cancellation. */
fun interface AudioCapture {
    suspend fun capture(request: AudioCaptureRequest): PortResult<AudioCaptureMeasurement>
}

/** Plays the requested tone then the bundled speech clip once, with a checked platform outcome. */
fun interface AudioPlayback {
    suspend fun play(request: AudioPlaybackRequest): PortResult<AudioPlaybackMeasurement>
}

/** The private audio-probe cache is cleared before each plan; created WAV paths are also readable by FileAccess. */
interface AudioSampleStore {
    suspend fun clear(): PortResult<Unit>
    suspend fun create(source: AudioCaptureSource, speaker: Boolean): PortResult<AudioSampleFile>
}

/** An explicit microphone foreground-service attempt is measured, then stopped when the plan ends. */
interface AudioForeground {
    suspend fun startMicrophone(): PortResult<MicrophoneForegroundOutcome>
    suspend fun stopMicrophone(): PortResult<Unit>
}

data class AudioSampleFile(val path: String, val target: FileWriteHandle)
data class AudioCaptureRequest(
    val source: AudioCaptureSource,
    val speaker: Boolean,
    val durationMs: Long,
    val sample: AudioSampleFile,
)
data class AudioPlaybackRequest(
    val stream: AudioPlaybackStream,
    val speaker: Boolean,
    val toneFrequencyHz: Int = 1_000,
    val toneDurationMs: Long = 1_500,
    val includeSpeech: Boolean = true,
)
enum class CallState { IDLE, RINGING, OFFHOOK }
enum class ProbeSpeaker { OFF, ON, BOTH }
enum class AudioCaptureSource { VOICE_CALL, VOICE_DOWNLINK, VOICE_UPLINK, VOICE_COMMUNICATION, VOICE_RECOGNITION, MIC, UNPROCESSED }
enum class AudioPlaybackStream { USAGE_VOICE_COMMUNICATION, USAGE_MEDIA }
enum class AudioInitialization { OK, FAILED }

data class AudioProbePlan(
    val durationMs: Long = 4_000,
    val speaker: ProbeSpeaker = ProbeSpeaker.BOTH,
    val inject: Boolean = true,
    val sources: List<AudioCaptureSource> = AudioCaptureSource.entries,
    val budgetMs: Long = 110_000,
    val routeBudgetMs: Long = 20_000,
)

data class AudioCaptureMeasurement(
    val source: AudioCaptureSource,
    val speaker: Boolean,
    val init: AudioInitialization,
    val bytes: Long,
    val rms: Double,
    val peak: Double,
    val silent: Boolean,
    val sample: String? = null,
    val error: String? = null,
)

data class AudioPlaybackMeasurement(
    val stream: AudioPlaybackStream,
    val speaker: Boolean,
    val played: Boolean,
    val error: String? = null,
)

data class SpeakerAttempt(val speaker: Boolean, val via: String, val switched: Boolean, val error: String? = null)
data class MicrophoneForegroundOutcome(val started: Boolean, val error: String? = null)
data class AudioProbeReport(
    val callState: CallState,
    val captures: List<AudioCaptureMeasurement>,
    val playback: List<AudioPlaybackMeasurement>,
    val speakerAttempts: List<SpeakerAttempt>,
    val foreground: MicrophoneForegroundOutcome,
    val truncated: Boolean = false,
)
