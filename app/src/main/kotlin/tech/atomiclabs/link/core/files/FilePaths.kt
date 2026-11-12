package tech.atomiclabs.link.core.files

import tech.atomiclabs.link.core.command.BadRequestException
import tech.atomiclabs.link.core.port.FileFailureKind

/** Applies path policy to canonical paths supplied by the filesystem adapter. */
class FilePaths(val sharedRoot: String, val shareCache: String, val probeCache: String) {
    fun resolve(path: String, realpath: (String) -> String): String {
        if (path.isBlank() || path.any { it == '\u0000' || it == '\r' || it == '\n' } ||
            path.split('/').any { it == ".." }) forbidden()
        val combined = if (path.startsWith('/')) path else "$sharedRoot/$path"
        val absolute = combined.split('/').filter { it.isNotEmpty() && it != "." }.joinToString("/", prefix = "/")
        val roots = listOf(sharedRoot, shareCache, probeCache)
        val lexicalRoot = roots.firstOrNull { contains(it, absolute) } ?: forbidden()
        val canonicalRoot = realpath(lexicalRoot)
        // Private cache children are approvals, not permission to follow a replaced cache root.
        if (lexicalRoot != sharedRoot && canonicalRoot != lexicalRoot) forbidden()
        val canonical = realpath(absolute)
        if (canonical.split('/').any { it == ".." } || !contains(canonicalRoot, canonical)) forbidden()
        return canonical
    }

    fun listedRoots(): List<String> = listOf("Download", "DCIM", "Pictures", "Documents")
        .map { "$sharedRoot/$it" } + shareCache

    fun resolveWrite(path: String, realpath: (String) -> String): String = resolve(path, realpath).also {
        if (contains(probeCache, it)) forbidden()
    }

    private fun contains(root: String, path: String) = path == root || path.startsWith(root.trimEnd('/') + "/")
    private fun forbidden(): Nothing = throw FilePathFailure(FileFailureKind.FORBIDDEN)

    companion object {
        const val MAX_BYTES = 256L * 1024 * 1024
        fun name(value: String): String {
            if (value.isBlank() || value in listOf(".", "..") ||
                value.any { it == '/' || it == '\\' || it.isISOControl() }) throw BadRequestException("name")
            return value
        }
    }
}

class FilePathFailure(val kind: FileFailureKind) : IllegalArgumentException("The file path is not available for this operation.")
