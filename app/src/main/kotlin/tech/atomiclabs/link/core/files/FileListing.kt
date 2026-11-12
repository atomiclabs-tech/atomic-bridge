package tech.atomiclabs.link.core.files

import kotlinx.serialization.json.*
import tech.atomiclabs.link.core.command.*
import tech.atomiclabs.link.core.port.*

internal class FileListing(private val files: FileAccess, private val operations: FileOperations) {
    suspend fun execute(body: BodyParams): CommandResult {
        val limit = body.int("limit", 200).coerceIn(1, 2_000)
        val path = body.string("path")
        val entries = if (path == null) operations.file("roots", files.roots()) else {
            val canonical = operations.file("resolve", files.resolve(path))
            val directory = operations.file("stat", files.stat(canonical))
            if (directory.kind != FileKind.DIRECTORY) throw BadRequestException("path")
            operations.file("list", files.list(canonical)).sortedBy { it.name }
        }
        val selected = entries.take(limit)
        return CommandResult.Success(buildJsonObject {
            put("entries", JsonArray(selected.map { entry -> buildJsonObject {
                put("name", entry.name); put("path", entry.path)
                put("kind", if (entry.kind == FileKind.DIRECTORY) "dir" else "file")
                put("size", entry.size); put("modified", entry.modified)
            } }))
            put("count", selected.size)
            put("truncated", entries.size > selected.size)
        })
    }
}
