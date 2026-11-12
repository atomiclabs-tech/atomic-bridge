package tech.atomiclabs.link.core.connection

/** Normalized user endpoint; parsing errors never include the supplied URL. */
@ConsistentCopyVisibility
data class RelayUrl private constructor(val websocketUrl: String, val httpBase: String, val encrypted: Boolean) {
    companion object {
        fun parse(input: String): RelayUrl {
            val match = Regex("^(ws|wss|http|https)://([^/?#]+)(/[^?#]*)?$", RegexOption.IGNORE_CASE)
                .matchEntire(input.trim()) ?: invalid()
            val scheme = match.groupValues[1].lowercase()
            val authority = match.groupValues[2]
            if (authority.any { it.isWhitespace() || it in "@\\%" } || input.any { it.isISOControl() }) invalid()
            val host = validateAuthority(authority)
            var path = match.groupValues[3].trimEnd('/')
            if (path.any { it.isWhitespace() || it == '\\' } || path.split('/').any { it == "." || it == ".." }) invalid()
            if (path.endsWith("/ws")) path = path.removeSuffix("/ws")
            else if (path.endsWith("/health")) path = path.removeSuffix("/health")
            val encrypted = scheme == "wss" || scheme == "https"
            require(encrypted || isPrivateHost(host)) {
                "Unencrypted relay addresses are allowed only for private network addresses. Use wss:// or https://."
            }
            val base = authority.lowercase() + path
            return RelayUrl("${if (encrypted) "wss" else "ws"}://$base/ws",
                "${if (encrypted) "https" else "http"}://$base", encrypted)
        }

        private fun validateAuthority(authority: String): String {
            val host: String
            val port: String?
            if (authority.startsWith('[')) {
                val end = authority.indexOf(']')
                if (end < 2) invalid()
                host = authority.substring(1, end)
                if (!host.contains(':') || !host.all { it in "0123456789abcdefABCDEF:" }) invalid()
                val tail = authority.substring(end + 1)
                if (tail.isNotEmpty() && !tail.startsWith(':')) invalid()
                port = if (tail.isEmpty()) null else tail.drop(1)
            } else {
                if (authority.count { it == ':' } > 1) invalid()
                host = authority.substringBefore(':')
                port = if (':' in authority) authority.substringAfter(':') else null
                if (host.isEmpty() || host.split('.').any {
                    it.isEmpty() || it.startsWith('-') || it.endsWith('-') || !it.all { c -> c.isLetterOrDigit() || c == '-' }
                }) invalid()
            }
            if (port != null && (port.toIntOrNull() !in 1..65535 || !port.all { it in '0'..'9' })) invalid()
            return host
        }

        private fun isPrivateHost(host: String): Boolean {
            if (host.equals("localhost", ignoreCase = true)) return true
            if (':' in host) return isPrivateIpv6(host)
            // Resolve no names, and reject alternate numeric spellings before transport canonicalization.
            val parts = host.split('.')
            if (parts.size != 4 || parts.any {
                it.length !in 1..3 || it.any { digit -> digit !in '0'..'9' } || it.length > 1 && it.startsWith('0')
            }) return false
            val octets = parts.map { it.toInt() }
            if (octets.any { it > 255 }) return false
            return when (octets[0]) {
                10, 127 -> true
                172 -> octets[1] in 16..31
                192 -> octets[1] == 168
                100 -> octets[1] in 64..127
                169 -> octets[1] == 254
                else -> false
            }
        }

        private fun isPrivateIpv6(host: String): Boolean {
            val compression = host.indexOf("::")
            if (compression != host.lastIndexOf("::")) return false
            val halves = if (compression < 0) listOf(host) else
                listOf(host.substring(0, compression), host.substring(compression + 2))
            val groups = halves.map { half ->
                if (half.isEmpty()) emptyList() else half.split(':').map { word ->
                    if (word.length !in 1..4) return false
                    word.toIntOrNull(16) ?: return false
                }
            }
            val count = groups.sumOf { it.size }
            if (compression < 0 && count != 8 || compression >= 0 && count >= 8) return false
            val words = if (compression < 0) groups.single() else
                groups[0] + List(8 - count) { 0 } + groups[1]
            return words[0] in 0xfc00..0xfdff || words[0] in 0xfe80..0xfebf ||
                words.take(7).all { it == 0 } && words[7] == 1
        }

        private fun invalid(): Nothing = throw IllegalArgumentException("The relay URL is invalid.")
    }
}
