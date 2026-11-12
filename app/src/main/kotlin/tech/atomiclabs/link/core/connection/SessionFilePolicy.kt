package tech.atomiclabs.link.core.connection

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import tech.atomiclabs.link.core.command.FailureDiagnostics
import tech.atomiclabs.link.core.log.Logger
import tech.atomiclabs.link.core.port.*

/** The completed SessionStore: four bounded attempts fit inside the supervisor's 60 s load budget. */
class SessionFilePolicy(
    private val files: Files,
    private val cipher: SessionCipher,
    private val clock: Clock,
    private val logger: Logger,
) : SessionStore {
    /** One-shot encrypted file access; replace stages, archives when requested, and commits atomically. */
    interface Files {
        suspend fun read(): ReadValue<ByteArray>
        suspend fun replace(encrypted: ByteArray, preserveUnreadable: Boolean): PortResult<Unit>
    }

    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private var unreadable: Boolean? = null

    override suspend fun read(): ReadValue<StoredSession> = mutex.withLock {
        var last: PortFailure? = null
        repeat(4) { index ->
            try {
                val value = withTimeout(8_000) { loadOnce() }
                unreadable = false
                return@withLock value
            } catch (cause: Exception) {
                if (cause is CancellationException && cause !is TimeoutCancellationException) throw cause
                last = failure("read", cause, "state=loading attempt=${index + 1}")
                if (index < 3) clock.sleep(1_000)
            }
        }
        unreadable = true
        ReadValue.Unreadable(checkNotNull(last))
    }

    override suspend fun save(session: StoredSession): PortResult<Unit> = mutex.withLock {
        try {
            withTimeout(8_000) {
                // Startup normally classifies the old file. A direct save must preserve it just as safely.
                if (unreadable == null) {
                    unreadable = try { loadOnce(); false }
                    catch (cause: Exception) {
                        if (cause is CancellationException) throw cause
                        failure("inspectBeforeSave", cause, "state=preserving")
                        true
                    }
                }
                val content = Content.from(session)
                content.validate()
                val plain = json.encodeToString(content).encodeToByteArray()
                val encrypted = try { checked("encrypt", cipher.encrypt(plain)) } finally { plain.fill(0) }
                checked("replace", files.replace(encrypted, unreadable == true))
                unreadable = false
                PortResult.Success(Unit)
            }
        } catch (cause: Exception) {
            if (cause is CancellationException && cause !is TimeoutCancellationException) throw cause
            PortResult.Failure(failure("save", cause, "state=save_failed"))
        }
    }

    private suspend fun loadOnce(): ReadValue<StoredSession> = when (val raw = files.read()) {
        ReadValue.Absent -> ReadValue.Absent
        is ReadValue.Unreadable -> throw StepFailure("readFile", raw.failure)
        is ReadValue.Present -> {
            val plain = checked("decrypt", cipher.decrypt(raw.value))
            try {
                val content = json.decodeFromString<Content>(plain.decodeToString(throwOnInvalidSequence = true))
                content.validate()
                ReadValue.Present(content.session())
            } finally { plain.fill(0) }
        }
    }

    private fun <T> checked(operation: String, result: PortResult<T>): T = when (result) {
        is PortResult.Success -> result.value
        is PortResult.Failure -> throw StepFailure(operation, result.failure)
    }

    private fun failure(operation: String, cause: Exception, state: String): PortFailure {
        val safe = FailureDiagnostics.sanitized(if (cause is StepFailure) cause.failure.cause else cause)
        val value = if (cause is StepFailure) cause.failure.copy(cause = safe) else PortFailure(
            if (cause is TimeoutCancellationException) FailureKind.TIMED_OUT
            else if (cause is IllegalArgumentException) FailureKind.INVALID_DATA else FailureKind.IO, safe,
        )
        logger.error("SessionFilePolicy", if (cause is StepFailure) cause.operation else operation,
            "path=session.bin $state", safe)
        return value
    }

    private class StepFailure(val operation: String, val failure: PortFailure) :
        Exception("A session operation failed.", failure.cause)

    @Serializable
    private data class Content(
        @SerialName("session_token") val token: String,
        @SerialName("refresh_token") val refresh: String?,
        @SerialName("expires_at") val expires: Long?,
        val grants: Map<String, Long?>,
        @SerialName("saved_at") val saved: Long,
    ) {
        fun validate() {
            require(token.isNotBlank() && (refresh == null || refresh.isNotBlank())) { "Session credentials are invalid." }
        }
        fun session() = StoredSession(token, refresh, expires, grants, saved)
        companion object {
            fun from(value: StoredSession) = Content(value.sessionToken, value.refreshToken,
                value.expiresAtEpochSeconds, value.grants, value.savedAtEpochMs)
        }
    }
}
