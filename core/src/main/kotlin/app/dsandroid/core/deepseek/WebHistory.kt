package app.dsandroid.core.deepseek

import app.dsandroid.core.agent.TurnStep
import app.dsandroid.core.tools.Artifact
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/**
 * [agent] marks a user turn sent in agent mode. Assistant turns from agent mode keep their timeline in
 * [steps] (reasoning, tool calls, answers) and generated files in [artifacts]; [codeRuns] holds Run-button
 * executions of code blocks. [revisionOf] links an "Ask DeepSeek to change this" request and its reply to the
 * workbench artifact they revise. All default to empty, so older history files still load.
 */
@Serializable
data class WebMessage(
    val id: String, val user: Boolean, val text: String, val thinking: String = "",
    val serverId: Long? = null, val parentId: Long? = null, val prompt: String? = null,
    val thinkingEnabled: Boolean = false, val searchEnabled: Boolean = false,
    val refFileIds: List<String> = emptyList(),
    val agent: Boolean = false,
    val steps: List<TurnStep> = emptyList(),
    val artifacts: List<Artifact> = emptyList(),
    val codeRuns: List<TurnStep.ToolCall> = emptyList(),
    val revisionOf: String? = null,
    val thinkingSeconds: Double? = null,
    val citations: List<WebCitation> = emptyList(),
    /** Files sent with a user message, shown as chips above its bubble. */
    val files: List<WebAttachment> = emptyList(),
    /** Like ("GOOD") or Dislike ("BAD") on a reply, as DeepSeek's message_feedback stores it; null for none. */
    val feedback: String? = null,
    /** The thinking and search steps of a reply that searched or used DeepSeek's tools (WebReply.timeline). */
    val timeline: List<WebStep> = emptyList(),
)

/** A file sent to DeepSeek: its own name, size when known, and DeepSeek's file id. */
@Serializable
data class WebAttachment(val name: String, val bytes: Long? = null, val id: String? = null)

/**
 * [updatedAt] (epoch milliseconds, 0 in history from before v1.0.0) orders the drawer's date groups. [pinned] follows
 * DeepSeek for chats it keeps. [remote] marks a chat DeepSeek lists that has not been opened here (its messages load
 * when it is opened); [syncedAt] is DeepSeek's time of the chat when its messages were last loaded or continued here,
 * [remoteUpdatedAt] the time DeepSeek's list last reported, so a chat continued on another device reloads when opened.
 */
@Serializable
data class WebChat(val id: String, val title: String, val sessionId: String? = null, val parentId: Long? = null,
    val messages: List<WebMessage> = emptyList(), val blocked: Boolean = false, val updatedAt: Long = 0, val pinned: Boolean = false,
    val remote: Boolean = false, val syncedAt: Long = 0, val remoteUpdatedAt: Long = 0)

/** Private local history only. No credentials, cookies, headers, or captured web documents. */
class WebHistory(private val file: File) {
    companion object {
        const val MAX_CHATS = 300
        const val MAX_BYTES = 8 * 1024 * 1024
    }
    private val json = Json { ignoreUnknownKeys = true }
    fun load(): List<WebChat> {
        if (!file.exists()) return emptyList()
        require(file.length() <= MAX_BYTES) { "Local chat history is too large." }
        return json.decodeFromString<List<WebChat>>(file.readText()).take(MAX_CHATS)
    }
    fun save(chats: List<WebChat>) {
        val content = fit(chats.take(MAX_CHATS))
        val temporary = File(file.parentFile, "${file.name}.tmp")
        temporary.writeText(content)
        Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
    /**
     * Over the size limit, the oldest chats DeepSeek also keeps (plain chats, no agent files) drop their local copy of the
     * messages and become remote entries that load again when opened; history that exists only here is never dropped.
     */
    private fun fit(chats: List<WebChat>): String {
        var current = chats
        var content = json.encodeToString(current)
        if (content.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) return content
        val candidates = chats.withIndex().filter { (_, chat) -> chat.sessionId != null && !chat.blocked && chat.messages.isNotEmpty() &&
            chat.messages.none { it.agent || it.artifacts.isNotEmpty() || it.steps.isNotEmpty() || it.codeRuns.isNotEmpty() } }
            .sortedBy { it.value.updatedAt }.map { it.index }
        for (index in candidates) {
            current = current.toMutableList().also { it[index] = it[index].copy(messages = emptyList(), remote = true, syncedAt = 0) }
            content = json.encodeToString(current)
            if (content.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) return content
        }
        throw IllegalArgumentException("Local chat history is full.")
    }
    fun clear() { Files.deleteIfExists(file.toPath()); Files.deleteIfExists(File(file.parentFile, "${file.name}.tmp").toPath()) }
}
