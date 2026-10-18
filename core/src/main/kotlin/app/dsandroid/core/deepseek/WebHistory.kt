package app.dsandroid.core.deepseek

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

@Serializable
data class WebMessage(
    val id: String, val user: Boolean, val text: String, val thinking: String = "",
    val serverId: Long? = null, val parentId: Long? = null, val prompt: String? = null,
    val thinkingEnabled: Boolean = false, val searchEnabled: Boolean = false,
    val refFileIds: List<String> = emptyList(),
)
@Serializable
data class WebChat(val id: String, val title: String, val sessionId: String? = null, val parentId: Long? = null,
    val messages: List<WebMessage> = emptyList(), val blocked: Boolean = false)

/** Private local history only. No credentials, cookies, headers, or captured web documents. */
class WebHistory(private val file: File) {
    private val json = Json { ignoreUnknownKeys = true }
    fun load(): List<WebChat> {
        if (!file.exists()) return emptyList()
        require(file.length() <= 8 * 1024 * 1024) { "Local chat history is too large." }
        return json.decodeFromString<List<WebChat>>(file.readText()).also { require(it.size <= 100) }
    }
    fun save(chats: List<WebChat>) {
        require(chats.size <= 100)
        val content = json.encodeToString(chats)
        require(content.toByteArray(Charsets.UTF_8).size <= 8 * 1024 * 1024) { "Local chat history is full." }
        val temporary = File(file.parentFile, "${file.name}.tmp")
        temporary.writeText(content)
        Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
    fun clear() { Files.deleteIfExists(file.toPath()); Files.deleteIfExists(File(file.parentFile, "${file.name}.tmp").toPath()) }
}
