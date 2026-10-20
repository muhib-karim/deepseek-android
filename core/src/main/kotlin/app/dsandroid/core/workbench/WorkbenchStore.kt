package app.dsandroid.core.workbench

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Artifact histories for one account, one JSON file per artifact. Kept outside the agent's chat folders so
 * tools cannot rewrite them; writes are atomic and unreadable files are treated as missing.
 */
class WorkbenchStore(private val directory: File) {
    private val json = Json { ignoreUnknownKeys = true }

    @Synchronized fun load(id: String): WorkbenchArtifact? {
        if (!id.matches(ID)) return null
        val file = File(directory, "$id.json")
        if (!file.isFile || file.length() > MAX_FILE_BYTES) return null
        return runCatching { json.decodeFromString<WorkbenchArtifact>(file.readText(Charsets.UTF_8)) }.getOrNull()?.takeIf { it.id == id }
    }

    @Synchronized fun save(artifact: WorkbenchArtifact) {
        val content = json.encodeToString(artifact)
        require(content.toByteArray(Charsets.UTF_8).size <= MAX_FILE_BYTES) { "Artifact history is too large" }
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create the artifact folder" }
        val temporary = File(directory, "${artifact.id}-${UUID.randomUUID()}.tmp")
        try {
            temporary.writeText(content, Charsets.UTF_8)
            Files.move(temporary.toPath(), File(directory, "${artifact.id}.json").toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally { temporary.delete() }
    }

    @Synchronized fun forChat(chatId: String): List<WorkbenchArtifact> = ids().mapNotNull { load(it) }.filter { it.chatId == chatId }

    @Synchronized fun deleteChat(chatId: String) { forChat(chatId).forEach { File(directory, "${it.id}.json").delete() } }

    @Synchronized fun clear() { directory.deleteRecursively() }

    private fun ids(): List<String> = directory.listFiles().orEmpty()
        .filter { it.isFile && it.name.endsWith(".json") }.map { it.name.removeSuffix(".json") }.filter { it.matches(ID) }.sorted()

    companion object {
        /** A full history (WorkbenchArtifact.MAX_HISTORY_BYTES) after JSON escaping. */
        const val MAX_FILE_BYTES = 16 * 1024 * 1024
        private val ID = Regex("[a-z0-9]{8,64}")
    }
}
