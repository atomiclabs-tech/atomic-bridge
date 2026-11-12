package tech.atomiclabs.link.core.setup

import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import tech.atomiclabs.link.core.command.FailureDiagnostics
import tech.atomiclabs.link.core.connection.RelayUrl

/** Parses an untrusted offer without changing settings or opening a connection. */
data class PairingLink(val relayUrl: String, val code: String) {
    override fun toString(): String = "PairingLink(private values redacted)"

    companion object {
        fun parse(uri: String): PairingLink {
            val match = Regex("^([^:]+)://([^/?#]+)\\?([^#]*)$").matchEntire(uri) ?: invalid()
            if (!match.groupValues[1].equals("atomic", ignoreCase = true) || match.groupValues[2] != "pair") invalid()
            val parts = match.groupValues[3].split('&').map { part ->
                val values = part.split('=', limit = 2)
                if (values.size != 2) invalid()
                decode(values[0]) to decode(values[1])
            }
            if (parts.size != 2 || parts.map { it.first }.toSet() != setOf("relay", "code")) invalid()
            val query = parts.toMap()
            val code = query.getValue("code").uppercase(Locale.ROOT)
            if (code.length != 6 || code.any { it !in 'A'..'Z' && it !in '0'..'9' }) invalid()
            val relay = try { RelayUrl.parse(query.getValue("relay")).httpBase }
            catch (cause: IllegalArgumentException) {
                throw IllegalArgumentException("The pairing link contains an invalid relay address.", FailureDiagnostics.sanitized(cause))
            }
            return PairingLink(relay, code)
        }

        private fun decode(value: String): String = try {
            URLDecoder.decode(value, StandardCharsets.UTF_8.name())
        } catch (cause: IllegalArgumentException) {
            throw IllegalArgumentException("The pairing link is invalid.", FailureDiagnostics.sanitized(cause))
        }

        private fun invalid(): Nothing = throw IllegalArgumentException("The pairing link is invalid.")
    }
}
