package app.dsandroid.core.assistants

import app.dsandroid.core.llm.LlmJson
import app.dsandroid.core.persistence.JsonFileStore
import app.dsandroid.core.personalization.ResponseStyle
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

enum class AssistantIcon { SPARK, BOOK, CODE, PEN }

@Serializable
data class SavedAssistant(
    val id: String,
    val name: String,
    val icon: AssistantIcon = AssistantIcon.SPARK,
    val instructions: String = "",
    val defaultTools: Set<String> = emptySet(),
    val responseStyle: ResponseStyle = ResponseStyle.DEFAULT,
)

class AssistantStore(directory: File) {
    private val files = JsonFileStore(directory, 128 * 1024)
    @Synchronized fun all(): List<SavedAssistant> = files.ids().mapNotNull { id ->
        runCatching { files.read(id)?.let { LlmJson.decodeFromString<SavedAssistant>(it) }?.takeIf { it.id == id } }.getOrNull()
    }.sortedBy { it.name.lowercase() }
    @Synchronized fun save(value: SavedAssistant) {
        require(value.name.isNotBlank() && value.name.length <= 80 && value.instructions.length <= 8000)
        require(value.defaultTools.size <= 128 && value.defaultTools.all { Regex("[a-zA-Z0-9_-]{1,100}").matches(it) })
        require(all().size < 50 || all().any { it.id == value.id })
        files.write(value.id, export(value))
    }
    @Synchronized fun delete(id: String) = files.delete(id)
    fun export(value: SavedAssistant): String = LlmJson.encodeToString(value)
}
