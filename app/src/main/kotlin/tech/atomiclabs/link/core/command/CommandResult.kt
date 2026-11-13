package tech.atomiclabs.link.core.command

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Extra fields cannot replace the success/error/code contract; failures use a plain sentence. */
sealed class CommandResult {
    data class Success(val fields: JsonObject = JsonObject(emptyMap())) : CommandResult()
    data class Failure(
        val code: ErrorCode,
        val error: String,
        val fields: JsonObject = JsonObject(emptyMap()),
    ) : CommandResult()

    fun toJson(): JsonObject {
        val fields = when (this) { is Success -> fields; is Failure -> fields }
        require(fields.keys.none { it in reserved }) { "Result fields contain a reserved key." }
        return buildJsonObject {
            fields.forEach { (key, value) -> put(key, value) }
            put("success", this@CommandResult is Success)
            if (this@CommandResult is Failure) {
                require(error.isNotBlank() && '\n' !in error && '\r' !in error) {
                    "A failure needs one plain sentence."
                }
                put("error", error)
                put("code", code.wireValue)
            }
        }
    }

    private companion object { val reserved = setOf("success", "error", "code") }
}

/** Preserves a route's side-effect outcome when cancellation interrupts its deadline context. */
internal class InterruptedCommandException(val result: CommandResult, cause: Throwable) :
    IllegalStateException("The command was interrupted.", cause)
