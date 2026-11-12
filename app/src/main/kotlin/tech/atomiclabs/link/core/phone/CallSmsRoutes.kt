// Adapted from upstream app/src/main/kotlin/com/hermesandroid/relay/accessibility/ActionExecutor.kt at da782a8.
package tech.atomiclabs.link.core.phone
import kotlinx.serialization.json.*
import tech.atomiclabs.link.core.command.*
import tech.atomiclabs.link.core.log.Logger
import tech.atomiclabs.link.core.port.*

class CallSmsRoutes(private val calls: CallControl, private val sms: SmsSender, private val logger: Logger) : RouteGroup {
    override val routes = listOf(
        Route("POST", "/call", { RouteNeeds(display = true) }) { body ->
            val number = nonBlank(body, "number")
            when (val result = calls.call(number)) {
                is PortResult.Success -> CommandResult.Success(buildJsonObject { put("number", number); put("mode", "auto_dial") })
                is PortResult.Failure -> failed("call", result.failure, ErrorCode.INTERNAL_ERROR)
            }
        },
        Route("POST", "/send_sms") { params ->
            val to = nonBlank(params, "to")
            val body = params.requiredString("body")
            if (body.isEmpty()) throw BadRequestException("body")
            when (val outcome = sms.send(to, body)) {
                is SmsOutcome.Sent -> {
                    check(outcome.parts > 0) { "SMS result has no parts." }
                    CommandResult.Success(buildJsonObject {
                        put("status", "sent"); put("to", to); put("length", body.length); put("parts", outcome.parts)
                    })
                }
                is SmsOutcome.Failed -> failed("send", outcome.failure, ErrorCode.SMS_FAILED)
                is SmsOutcome.RadioFailure -> failed("radio", outcome.failure, ErrorCode.SMS_FAILED,
                    buildJsonObject { put("android_result", outcome.androidResult) })
                is SmsOutcome.Unconfirmed -> failed("radioWait",
                    PortFailure(FailureKind.TIMED_OUT, IllegalStateException("SMS acknowledgement is unconfirmed.")),
                    ErrorCode.SMS_UNCONFIRMED, buildJsonObject { put("parts", outcome.parts) })
            }
        },
    )
    private fun nonBlank(body: BodyParams, name: String): String =
        body.requiredString(name).also { if (it.isBlank()) throw BadRequestException(name) }
    private fun failed(operation: String, problem: PortFailure, code: ErrorCode,
        fields: JsonObject = JsonObject(emptyMap())): CommandResult {
        logger.error("CallSmsRoutes", operation, "state=failed", FailureDiagnostics.sanitized(problem.cause))
        val denied = problem.kind == FailureKind.PERMISSION_DENIED
        return CommandResult.Failure(if (denied) ErrorCode.PERMISSION_DENIED else code,
            when { denied -> "A phone permission is required."
                code == ErrorCode.SMS_UNCONFIRMED -> "The radio did not confirm the SMS."
                else -> "The phone operation failed." },
            buildJsonObject {
                fields.forEach { (key, value) -> put(key, value) }
                if (denied) put("permission", checkNotNull(problem.permission))
            })
    }
}
