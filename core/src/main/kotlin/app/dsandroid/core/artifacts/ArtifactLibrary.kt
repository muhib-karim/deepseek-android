package app.dsandroid.core.artifacts

import app.dsandroid.core.agent.TurnStep
import app.dsandroid.core.guard.WorkspacePath
import app.dsandroid.core.persistence.ConversationRecord
import app.dsandroid.core.persistence.JsonFileStore
import app.dsandroid.core.tools.Artifact
import java.nio.file.Files
import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

// Titles are resolved from live chat records, so renames and chat deletion cannot leave stale links.
enum class LibraryType { ALL, DOCUMENT, IMAGE, ARTIFACT, ARCHIVE, OTHER }
data class LibraryEntry(val artifact: Artifact, val conversationId: String?, val chatTitle: String?, val bytes: Long, val modifiedAt: Long) {
    val type: LibraryType get() = when {
        artifact.mimeType == WebProject.MIME || artifact.mimeType in setOf("text/html", "image/svg+xml", "text/markdown", "text/vnd.mermaid") -> LibraryType.ARTIFACT
        artifact.mimeType.startsWith("image/") -> LibraryType.IMAGE
        artifact.mimeType == "application/zip" -> LibraryType.ARCHIVE
        artifact.mimeType.startsWith("text/") || artifact.mimeType == "application/pdf" || artifact.mimeType.contains("officedocument") || artifact.mimeType == "application/json" -> LibraryType.DOCUMENT
        else -> LibraryType.OTHER
    }
}

class ArtifactLibrary(private val workspace: WorkspacePath) {
    private val metadata = JsonFileStore(workspace.resolve(".library"), 16 * 1024)
    @Serializable private data class Source(val artifact: Artifact, val conversationId: String?, val temporary: Boolean = false, val deleted: Boolean = false)
    private fun key(path: String) = MessageDigest.getInstance("SHA-256").digest(path.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun source(path: String): Source? = metadata.read(key(path))?.let { runCatching { Json.decodeFromString<Source>(it) }.getOrNull() }

    fun record(artifact: Artifact, conversationId: String?, temporary: Boolean = false): Unit = synchronized(lock) {
        val file = workspace.resolve(artifact.path)
        require(file.isFile && artifact.fileName == file.name && artifact.path.split('/').none { it.startsWith('.') })
        val old = source(artifact.path)
        // A branch does not take ownership from the original chat; a temporary branch never hides a saved file.
        if (old == null || old.deleted || (old.temporary && !temporary)) {
            metadata.write(key(artifact.path), Json.encodeToString(Source(artifact, conversationId, temporary)))
        }
    }

    fun isDeleted(artifact: Artifact): Boolean = synchronized(lock) { source(artifact.path)?.deleted == true }

    fun inherit(original: Artifact, generated: Artifact) = synchronized(lock) {
        source(original.path)?.let { record(generated, it.conversationId, it.temporary) }
    }

    fun list(records: List<ConversationRecord>): List<LibraryEntry> = synchronized(lock) {
        val store = ArtifactStore(workspace)
        records.filterNot { it.temporary }.forEach { chat ->
            chat.items.forEach { item ->
                item.artifacts.forEach { artifact ->
                    if (runCatching { workspace.resolve(artifact.path).isFile }.getOrDefault(false)) record(artifact, chat.id)
                }
                item.steps.forEachIndexed { stepIndex, step ->
                    if (step is TurnStep.Answer) FencedArtifacts.parse(step.text).forEachIndexed { fenceIndex, fence ->
                        runCatching<Unit> {
                            val ref = store.fenceReference(item.id, stepIndex * 100 + fenceIndex, fence.format, fence.content)
                            if (source(ref.path)?.deleted != true) {
                                record(store.importFence(item.id, stepIndex * 100 + fenceIndex, fence.format, fence.content), chat.id)
                            }
                        }
                    }
                }
            }
        }
        // Associate edits with the source chat, including versions created by the existing canvas.
        metadata.ids().forEach { id ->
            val entry = metadata.read(id)?.let { runCatching { Json.decodeFromString<Source>(it) }.getOrNull() }
            if (entry != null) runCatching { store.history(entry.artifact) }.getOrDefault(emptyList()).forEach { version ->
                record(version, entry.conversationId, entry.temporary)
            }
        }
        val chats = records.filterNot { it.temporary }.associateBy { it.id }
        val result = mutableListOf<LibraryEntry>()
        fun visit(directory: java.io.File, depth: Int) {
            if (depth > 32) return
            directory.listFiles().orEmpty().forEach { file ->
                if (file.name.startsWith('.') || Files.isSymbolicLink(file.toPath())) return@forEach
                val path = workspace.relative(file)
                if (runCatching { workspace.resolve(path) }.getOrNull() != file.canonicalFile) return@forEach
                if (file.isDirectory) visit(file, depth + 1)
                else if (file.isFile) {
                    val saved = source(path)
                    if (saved?.temporary == true) return@forEach
                    val artifact = saved?.artifact ?: Artifact(file.name, mime(file.extension.lowercase()), path)
                    val chat = saved?.conversationId?.let(chats::get)
                    result += LibraryEntry(artifact, chat?.id, chat?.title, file.length(), file.lastModified())
                }
            }
        }
        visit(workspace.root, 0)
        result.sortedByDescending { it.modifiedAt }
    }

    fun delete(artifact: Artifact) = synchronized(lock) {
        require(artifact.path.split('/').none { it.startsWith('.') })
        val file = workspace.resolve(artifact.path)
        require(!file.exists() || file.isFile)
        check(!file.exists() || file.delete())
        // A tombstone keeps automatic fence imports and message cards from recreating the file.
        val old = source(artifact.path) ?: Source(artifact, null)
        metadata.write(key(artifact.path), Json.encodeToString(old.copy(deleted = true)))
    }

    companion object {
        private val lock = Any()
        fun filter(entries: List<LibraryEntry>, query: String, type: LibraryType): List<LibraryEntry> = entries.filter {
            (type == LibraryType.ALL || it.type == type) &&
                (it.artifact.fileName.contains(query.trim(), true) || it.chatTitle.orEmpty().contains(query.trim(), true))
        }
        private fun mime(extension: String): String = when (extension) {
            "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
            "pdf" -> "application/pdf"; "html", "htm" -> "text/html"; "svg" -> "image/svg+xml"
            "md" -> "text/markdown"; "mmd" -> "text/vnd.mermaid"; "webproj" -> WebProject.MIME
            "png" -> "image/png"; "jpg", "jpeg" -> "image/jpeg"; "webp" -> "image/webp"; "gif" -> "image/gif"
            "zip" -> "application/zip"; "json" -> "application/json"; "csv" -> "text/csv"
            "txt", "css", "js", "kt", "py" -> "text/plain"
            else -> "application/octet-stream"
        }
    }
}
