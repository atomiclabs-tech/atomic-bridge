package tech.atomiclabs.link.core.command

/** Keeps failure types and stack frames while removing messages that may contain private payloads. */
object FailureDiagnostics {
    fun sanitized(cause: Throwable): Throwable = copy(cause, 0, mutableListOf())

    fun requestContext(requestId: String, registeredPath: String?): String {
        val safeId = if (uuid.matches(requestId)) requestId else "<invalid>"
        return "request_id=$safeId path=${registeredPath ?: "<unregistered>"}"
    }

    private fun copy(original: Throwable, depth: Int, visited: MutableList<Throwable>): Throwable {
        visited += original
        val result = Exception("Failure type: ${original::class.qualifiedName}; messages redacted, graph bounded.")
        result.stackTrace = original.stackTrace.copyOf()
        if (depth < 8) {
            original.cause?.let {
                if (canCopy(it, visited)) result.initCause(copy(it, depth + 1, visited))
            }
            original.suppressed.take(8).forEach {
                if (canCopy(it, visited)) result.addSuppressed(copy(it, depth + 1, visited))
            }
        }
        return result
    }

    private fun canCopy(cause: Throwable, visited: List<Throwable>): Boolean =
        visited.size < 32 && visited.none { it === cause }

    private val uuid = Regex("[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}")
}
