package app.dsandroid.core.artifacts

import app.dsandroid.core.guard.WorkspacePath
import app.dsandroid.core.tools.Artifact
import app.dsandroid.core.tools.SvgSanitizer
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Original artifact records remain unchanged; only private journals describe edit history. */
class ArtifactStore(private val workspace: WorkspacePath, private val maxVersions: Int = 20) {
    init { require(maxVersions in 2..50) }

    fun create(format: String, title: String, content: String): Artifact = synchronized(lock) {
        val type = ArtifactFormat.parse(format)
        require(title.isNotBlank() && title.length <= 100 && title.matches(Regex("[A-Za-z0-9][A-Za-z0-9._ -]*"))) { "Use a simple artifact title" }
        require(!title.contains("--a")) { "Reserved artifact title" }
        val name = if (title.endsWith(".${type.extension}", true)) title else "$title.${type.extension}"
        val artifact = Artifact(name, type.mime, name)
        writeNew(artifact, type.prepare(content))
        // A killed initial journal write still leaves a valid, readable original artifact.
        saveJournal(key(artifact), Journal(artifact, listOf(artifact)))
        artifact
    }

    fun read(artifact: Artifact): String {
        val file = workspace.resolve(artifact.path)
        require(file.isFile && file.length() <= MAX_BYTES) { "Artifact unavailable or too large" }
        return file.readText(Charsets.UTF_8).also { require(it.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) }
    }

    fun history(artifact: Artifact): List<Artifact> = synchronized(lock) {
        val journal = loadJournal(artifact)
        // Missing older files cannot prevent the remaining versions from opening.
        journal.versions.filter { workspace.resolve(it.path).isFile }
            .also { require(it.isNotEmpty()) { "Artifact history unavailable" } }
    }

    fun edit(artifact: Artifact, content: String): Artifact = synchronized(lock) {
        val journal = loadJournal(artifact)
        val type = ArtifactFormat.forArtifact(journal.original)
        val prepared = type.prepare(content)
        val group = key(journal.original)
        val stem = journal.original.fileName.substringBeforeLast('.').take(40)
        val name = "$stem--a$group--${UUID.randomUUID()}.${type.extension}"
        val edited = Artifact(name, type.mime, name)
        writeNew(edited, prepared)
        val previous = journal.versions.filter { it != journal.original }
        val retained = (listOf(edited) + previous).take(maxVersions - 1) + journal.original
        // Publish a complete journal before pruning anything; interrupted writes never replace content.
        saveJournal(group, Journal(journal.original, retained))
        (journal.versions - retained.toSet()).forEach { old -> runCatching { workspace.resolve(old.path).delete() } }
        edited
    }

    /** Stable import identity allows reopening the same fence and its edits after process death. */
    fun importFence(turnId: String, index: Int, format: String, content: String): Artifact = synchronized(lock) {
        require(index >= 0)
        val type = ArtifactFormat.parse(format)
        val prepared = type.prepare(content)
        val artifact = fenceReference(turnId, index, format, content)
        val name = artifact.fileName
        val file = workspace.resolve(name)
        if (file.exists()) {
            require(file.isFile && read(artifact) == prepared) { "Fence import collision" }
            artifact
        } else create(type.extension, name, prepared)
    }

    fun fenceReference(turnId: String, index: Int, format: String, content: String): Artifact {
        require(index >= 0)
        val type = ArtifactFormat.parse(format)
        val identity = digest("$turnId\u0000$index\u0000${type.extension}\u0000$content")
        val name = "fence_$identity.${type.extension}"
        return Artifact(name, type.mime, name)
    }

    private fun writeNew(artifact: Artifact, content: String) {
        val file = workspace.resolve(artifact.path)
        require(file.parentFile == workspace.root && file.name == artifact.fileName)
        check(workspace.root.isDirectory || workspace.root.mkdirs()) { "Cannot create workspace" }
        FileChannel.open(file.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { channel ->
            val buffer = java.nio.ByteBuffer.wrap(content.toByteArray(Charsets.UTF_8))
            while (buffer.hasRemaining()) channel.write(buffer)
            channel.force(true)
        }
    }

    private fun key(artifact: Artifact): String = versionName.matchEntire(artifact.fileName)?.groupValues?.get(1) ?: digest(artifact.path)

    private fun loadJournal(artifact: Artifact): Journal {
        val file = journalFile(key(artifact))
        if (!file.exists()) {
            require(versionName.matchEntire(artifact.fileName) == null) { "Artifact history unavailable" }
            ArtifactFormat.forArtifact(artifact)
            return Journal(artifact, listOf(artifact))
        }
        require(file.isFile && file.length() <= 64 * 1024) { "Invalid artifact history" }
        val journal = json.decodeFromString<Journal>(file.readText())
        require(journal.versions.size in 1..50 && journal.original in journal.versions)
        require(key(journal.original) == key(artifact) && journal.versions.distinct().size == journal.versions.size)
        journal.versions.forEach {
            require(key(it) == key(artifact))
            val resolved = workspace.resolve(it.path)
            require(it == journal.original || resolved.parentFile == workspace.root)
            require(it.fileName == File(it.path).name && it.mimeType == journal.original.mimeType)
        }
        require(artifact == journal.original || artifact in journal.versions) { "Artifact version unavailable" }
        return journal
    }

    private fun journalFile(group: String): File = workspace.resolve(".artifact-history/$group.json")

    private fun saveJournal(group: String, journal: Journal) {
        val target = journalFile(group)
        check(target.parentFile.isDirectory || target.parentFile.mkdirs())
        val temporary = workspace.resolve(".artifact-history/$group-${UUID.randomUUID()}.tmp")
        try {
            FileChannel.open(temporary.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { channel ->
                val buffer = java.nio.ByteBuffer.wrap(json.encodeToString(journal).toByteArray(Charsets.UTF_8))
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { temporary.delete() }
    }

    @Serializable private data class Journal(val original: Artifact, val versions: List<Artifact>)

    companion object {
        const val MAX_BYTES = 2 * 1024 * 1024
        private val lock = Any()
        private val json = Json { ignoreUnknownKeys = true }
        private val versionName = Regex(".+--a([a-f0-9]{64})--[a-f0-9-]{36}\\.[A-Za-z0-9]+")
        private fun digest(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}

internal enum class ArtifactFormat(val extension: String, val mime: String) {
    HTML("html", "text/html"), SVG("svg", "image/svg+xml"), MERMAID("mmd", "text/vnd.mermaid"),
    MARKDOWN("md", "text/markdown"), CSV("csv", "text/csv"), TEXT("txt", "text/plain"), JSON("json", "application/json"), WEB_PROJECT("webproj", WebProject.MIME);

    fun prepare(content: String): String {
        require(content.toByteArray(Charsets.UTF_8).size <= ArtifactStore.MAX_BYTES) { "Artifact exceeds 2 MB" }
        return when (this) {
            SVG -> SvgSanitizer.sanitize(content)
            WEB_PROJECT -> WebProject.decode(content).encode()
            JSON -> content.also { Json.parseToJsonElement(it) }
            else -> content
        }.also { require(it.toByteArray(Charsets.UTF_8).size <= ArtifactStore.MAX_BYTES) { "Artifact exceeds 2 MB" } }
    }

    companion object {
        fun parse(value: String): ArtifactFormat = when (value.lowercase()) {
            "webproject", "webproj" -> WEB_PROJECT; "html", "htm" -> HTML; "svg" -> SVG; "mermaid", "mmd" -> MERMAID
            "markdown", "md" -> MARKDOWN; "csv" -> CSV; "text", "txt", "plain" -> TEXT; "json" -> JSON
            else -> throw IllegalArgumentException("Unsupported artifact format")
        }
        fun forArtifact(artifact: Artifact): ArtifactFormat = entries.firstOrNull {
            artifact.mimeType == it.mime && artifact.fileName.endsWith(".${it.extension}", true)
        } ?: throw IllegalArgumentException("Artifact cannot be edited")
    }
}
