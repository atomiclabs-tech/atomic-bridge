package tech.atomiclabs.link.core.phone

import kotlinx.serialization.json.*
import tech.atomiclabs.link.core.command.BadRequestException
import tech.atomiclabs.link.core.command.BodyParams
import tech.atomiclabs.link.core.port.IntentComponent
import tech.atomiclabs.link.core.port.IntentExtra
import tech.atomiclabs.link.core.port.IntentSpec as PlatformIntent

/** Parses the wire intent into platform-independent typed values, without exposing payloads in errors. */
object IntentSpec {
    fun parse(body: BodyParams, activity: Boolean = true): PlatformIntent = PlatformIntent(
        action = nonBlank(body.requiredString("action"), "action"),
        data = body.string("data"),
        packageName = body.string("package")?.let { nonBlank(it, "package") },
        component = if (activity) body.string("component")?.let(::component) else null,
        extras = body.objectValue("extras")?.mapValues { extra(it.value) }.orEmpty(),
        category = if (activity) body.string("category")?.let { nonBlank(it, "category") } else null,
    )

    private fun component(value: String): IntentComponent {
        val parts = value.split('/')
        if (parts.size != 2 || parts.any { it.isBlank() || it.any(Char::isWhitespace) }) {
            throw BadRequestException("component")
        }
        val (pkg, cls) = parts
        return IntentComponent(pkg, if (cls.startsWith('.')) pkg + cls else cls)
    }

    private fun extra(value: JsonElement): IntentExtra {
        val primitive = value as? JsonPrimitive ?: throw BadRequestException("extras")
        if (primitive is JsonNull) throw BadRequestException("extras")
        if (primitive.isString) return IntentExtra.Text(primitive.content)
        primitive.booleanOrNull?.let { return IntentExtra.Flag(it) }
        val raw = primitive.content
        if (integer.matches(raw)) {
            return IntentExtra.Integer(raw.toLongOrNull() ?: throw BadRequestException("extras"))
        }
        val number = raw.toDoubleOrNull()?.takeIf { it.isFinite() } ?: throw BadRequestException("extras")
        return IntentExtra.Decimal(number)
    }

    private fun nonBlank(value: String, name: String): String {
        if (value.isBlank()) throw BadRequestException(name)
        return value
    }

    private val integer = Regex("-?(0|[1-9][0-9]*)")
}
