package app.dsandroid.core.persistence

import app.dsandroid.core.agent.TurnStep
import app.dsandroid.core.llm.ChatMessage
import app.dsandroid.core.llm.LlmJson
import app.dsandroid.core.tools.Artifact
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

@Serializable
data class StoredChatItem(
    val id: String,
    val role: String,
    val text: String = "",
    val attachments: List<String> = emptyList(),
    val steps: List<TurnStep> = emptyList(),
    val artifacts: List<Artifact> = emptyList(),
)

@Serializable
data class ConversationRecord(
    val id: String,
    val title: String,
    val createdAt: Long,
    val history: List<ChatMessage>,
    val items: List<StoredChatItem>,
    val attachmentCount: Int = 0,
    val pendingRunId: String? = null,
)

/** Private JSON files, replaced atomically so a process exit cannot leave a partial record. */
class JsonFileStore(private val directory: File, private val maxBytes: Int = 64 * 1024 * 1024) {
    init { require(maxBytes > 0) }
    private fun file(id: String): File {
        require(Regex("[A-Za-z0-9_-]{1,100}").matches(id)) { "Invalid record identifier" }
        return File(directory, "$id.json")
    }

    @Synchronized fun write(id: String, json: String) {
        val target = file(id)
        val bytes = json.toByteArray(Charsets.UTF_8)
        require(bytes.size <= maxBytes) { "Record exceeds storage limit" }
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create record directory" }
        val temporary = File.createTempFile("record-", ".tmp", directory)
        try {
            FileOutputStream(temporary).use { output -> output.write(bytes); output.fd.sync() }
            try {
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally { temporary.delete() }
    }

    @Synchronized fun read(id: String): String? {
        val source = file(id)
        if (!source.exists()) return null
        require(source.isFile && source.length() <= maxBytes) { "Invalid record size" }
        return source.inputStream().bufferedReader(Charsets.UTF_8).use { it.readText() }
    }
    @Synchronized fun delete(id: String) {
        val target = file(id)
        check(!target.exists() || target.delete()) { "Could not delete record" }
    }
    @Synchronized fun ids(): List<String> = directory.listFiles().orEmpty()
        .filter { it.isFile && it.name.endsWith(".json") }
        .map { it.name.removeSuffix(".json") }.filter { Regex("[A-Za-z0-9_-]{1,100}").matches(it) }
}

class ConversationStore(directory: File) {
    private val files = JsonFileStore(directory)
    fun save(record: ConversationRecord) = files.write(record.id, LlmJson.encodeToString(record))
    fun delete(id: String) = files.delete(id)
    fun loadAll(): List<ConversationRecord> = files.ids().mapNotNull { id ->
        runCatching { files.read(id)?.let { LlmJson.decodeFromString<ConversationRecord>(it) }?.takeIf { it.id == id } }.getOrNull()
    }.sortedByDescending { it.createdAt }
}
