package tech.atomiclabs.link.core.status

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.withTimeout
import tech.atomiclabs.link.core.command.FailureDiagnostics
import tech.atomiclabs.link.core.log.Logger
import tech.atomiclabs.link.core.port.*
import tech.atomiclabs.link.core.protocol.Envelope

/** Emits immediately, every 30 s and on changed snapshots; platform event owners may signal refresh(). */
class StatusReporter(
    private val identity: DeviceIdentity,
    private val device: DeviceStateSource,
    private val permissions: PermissionState,
    private val clock: Clock,
    private val logger: Logger,
    private val notifications: NotificationSource?,
    private val newId: () -> String,
) {
    /** Existing composition keeps notification reporting disabled until its source is wired. */
    constructor(identity: DeviceIdentity, device: DeviceStateSource, permissions: PermissionState,
        clock: Clock, logger: Logger, newId: () -> String) : this(identity, device, permissions, clock, logger, null, newId)

    private val refreshes = Channel<Unit>(Channel.CONFLATED)

    fun refresh() { check(refreshes.trySend(Unit).isSuccess) { "Status refresh signal was refused." } }

    suspend fun run(deviceId: String, send: suspend (Envelope) -> PortResult<Unit>) {
        var previous: StatusPayload? = null
        var sentAt: Long? = null
        val ticks = flow {
            emit(Unit)
            while (true) { clock.sleep(250); emit(Unit) }
        }
        merge(ticks, refreshes.receiveAsFlow()).collect {
            val fields = StatusPayload(deviceId, read("identity") { identity.read() },
                read("device") { device.read() }, read("permissions") { permissions.read() },
                notifications?.let { source -> read("notifications") { source.isEnabled() } } ?: false)
            val now = clock.monotonicMs()
            val last = sentAt
            if (previous != fields || last == null || now - last >= 30_000) {
                read("send") { send(Envelope("bridge", "bridge.status", newId(), fields.toJson(clock.epochMs()))) }
                previous = fields
                sentAt = now
            }
        }
    }

    private suspend fun <T> read(operation: String, block: suspend () -> PortResult<T>): T {
        try {
            return withTimeout(10_000) {
                when (val result = block()) {
                    is PortResult.Success -> result.value
                    is PortResult.Failure -> throw IllegalStateException("A status operation failed.",
                        FailureDiagnostics.sanitized(result.failure.cause))
                }
            }
        } catch (cause: Exception) {
            if (cause is CancellationException && cause !is kotlinx.coroutines.TimeoutCancellationException) throw cause
            val safe = FailureDiagnostics.sanitized(cause)
            logger.error("StatusReporter", operation, "state=online", safe)
            throw IllegalStateException("Status reporting failed.", safe)
        }
    }
}
