// Adapted from upstream app/src/main/kotlin/com/hermesandroid/relay/accessibility/ActionExecutor.kt at da782a8.
package tech.atomiclabs.link.core.phone
import kotlinx.serialization.json.*
import tech.atomiclabs.link.core.command.*
import tech.atomiclabs.link.core.log.Logger
import tech.atomiclabs.link.core.port.*

class ContactsRoutes(private val contacts: ContactsSource, private val logger: Logger) : RouteGroup {
    override val routes = listOf(Route("POST", "/search_contacts") { body ->
        val query = body.requiredString("query")
        if (query.isBlank()) throw BadRequestException("query")
        val limit = body.int("limit", 20).coerceIn(1, 100)
        when (val result = contacts.search(query, limit)) {
            is PortResult.Success -> CommandResult.Success(buildJsonObject {
                put("query", query); put("count", result.value.size)
                put("contacts", buildJsonArray { result.value.forEach { contact -> add(buildJsonObject {
                    put("id", contact.id); put("name", contact.name)
                    put("phones", buildJsonArray { contact.phones.forEach { phone -> add(buildJsonObject {
                        put("number", phone.number); put("type", phone.type); put("label", phone.label)
                    }) } })
                }) } })
            })
            is PortResult.Failure -> {
                logger.error("ContactsRoutes", "search", "state=failed", FailureDiagnostics.sanitized(result.failure.cause))
                val denied = result.failure.kind == FailureKind.PERMISSION_DENIED
                CommandResult.Failure(if (denied) ErrorCode.PERMISSION_DENIED else ErrorCode.INTERNAL_ERROR,
                    "Contacts could not be read.", buildJsonObject {
                        if (denied) put("permission", checkNotNull(result.failure.permission))
                    })
            }
        }
    })
}
