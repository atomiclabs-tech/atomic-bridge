// forge-allow: no-test declarations only
package tech.atomiclabs.link.core.port

/** Adapters log a failure at its source, retain its cause, and return it without fabricating success. */
sealed interface PortResult<out T> {
    data class Success<T>(val value: T) : PortResult<T>
    data class Failure(val failure: PortFailure) : PortResult<Nothing>
}

/** Absence is legitimate missing data; unreadable data is a failure and must never be erased. */
sealed interface ReadValue<out T> {
    data class Present<T>(val value: T) : ReadValue<T>
    data object Absent : ReadValue<Nothing>
    data class Unreadable(val failure: PortFailure) : ReadValue<Nothing>
}

data class PortFailure(
    val kind: FailureKind,
    val cause: Throwable,
    val permission: String? = null,
    val platformCode: Int? = null,
    val httpStatus: Int? = null,
)

enum class FailureKind { UNAVAILABLE, PERMISSION_DENIED, REJECTED, TIMED_OUT, INVALID_DATA, IO, PLATFORM }
