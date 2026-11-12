package tech.atomiclabs.link.core.command

import kotlinx.coroutines.withTimeout

/** Includes queueing and preconditions in the relay's 25-second command budget. */
class CommandDeadline(private val timeoutMs: Long = 25_000) {
    init { require(timeoutMs > 0) { "The command deadline must be positive." } }

    suspend fun <T> run(block: suspend () -> T): T = withTimeout(timeoutMs) { block() }
}
