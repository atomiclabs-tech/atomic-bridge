package tech.atomiclabs.link.core.connection

import tech.atomiclabs.link.core.port.ConnectionPhase
import tech.atomiclabs.link.core.port.ConnectionFailureOrigin
import tech.atomiclabs.link.core.port.PortFailure

/** Public connection observations; credentials remain memory-only and are excluded from diagnostics. */
data class ConnectionState(
    val phase: ConnectionPhase = ConnectionPhase.UNPAIRED,
    val attempt: Long = 0,
    val reauthRetries: Int = 0,
    val rePairNeeded: Boolean = false,
    val failure: PortFailure? = null,
    val failureOrigin: ConnectionFailureOrigin = ConnectionFailureOrigin.NONE,
    val retryScheduled: Boolean = false,
) {
    val message: String? get() = if (rePairNeeded) "re-pair needed" else null
    override fun toString(): String = "ConnectionState(phase=$phase, attempt=$attempt, reauthRetries=$reauthRetries)"
}

internal class ConnectionFailure(val operation: String, val failure: PortFailure,
    val origin: ConnectionFailureOrigin = ConnectionFailureOrigin.INTERNAL) :
    Exception("A connection operation failed.", failure.cause)
