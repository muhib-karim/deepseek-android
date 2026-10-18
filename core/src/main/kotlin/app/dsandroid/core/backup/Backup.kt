package app.dsandroid.core.backup

import app.dsandroid.core.assistants.SavedAssistant
import app.dsandroid.core.persistence.ConversationRecord
import app.dsandroid.core.persistence.ConversationBranch
import app.dsandroid.core.agent.TurnStep
import app.dsandroid.core.agent.ToolStatus
import app.dsandroid.core.personalization.*
import java.io.InputStream
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable
data class BackupSettings(
    val appearance: String = "SYSTEM", val fontScale: Float = 1f,
    val collapseThinking: Boolean = true, val suggestedFollowUps: Boolean = true,
    val audioLanguage: String = "", val appLanguage: String = "",
    val memoryEnabled: Boolean = true, val memoryUpkeep: Boolean = false,
    val customInstructions: String = "", val disabledTools: Set<String> = emptySet(),
    val searchBackend: String = "duckduckgo",
)

/** Deliberately excludes provider/account binding and execution state. Restored tasks stay paused. */
@Serializable
data class BackupTask(val id: String, val title: String, val prompt: String, val runAt: Long,
    val intervalMinutes: Long, val model: String)

@Serializable
data class BackupArchive(
    val schemaVersion: Int = 1,
    val chats: List<ConversationRecord> = emptyList(),
    val projects: List<ProjectRecord> = emptyList(),
    val memory: List<SavedFact> = emptyList(),
    val personas: List<SavedAssistant> = emptyList(),
    val tasks: List<BackupTask> = emptyList(),
    val settings: BackupSettings = BackupSettings(),
)

object BackupCodec {
    const val MAX_BYTES = 16 * 1024 * 1024
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }
    private val id = Regex("[A-Za-z0-9_-]{1,100}")
    fun read(input: InputStream): BackupArchive {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(output.size() + count <= MAX_BYTES) { "Backup exceeds size limit" }
            output.write(buffer, 0, count)
        }
        // Reject invalid UTF-8 instead of silently changing a damaged backup.
        val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
        return decode(decoder.decode(java.nio.ByteBuffer.wrap(output.toByteArray())).toString())
    }
    fun encode(value: BackupArchive, credentials: Set<String> = emptySet()): String {
        val safe = value.copy(chats = value.chats.filterNot { it.temporary }.map {
            it.copy(pendingRunId = null, supersededRunIds = emptySet(), history = ConversationBranch.completeExchanges(it.history),
                items = it.items.map { item -> item.copy(steps = item.steps.map { step ->
                    when (step) {
                        is TurnStep.ToolCall -> if (step.status in setOf(ToolStatus.RUNNING, ToolStatus.WAITING)) step.copy(status = ToolStatus.STOPPED) else step
                        is TurnStep.Reasoning -> step.copy(complete = true)
                        is TurnStep.Answer -> step
                    }
                }) })
        })
        validate(safe)
        // Traverse strings rather than raw JSON so redaction cannot corrupt JSON escaping.
        fun scrub(element: JsonElement): JsonElement = when (element) {
            is JsonObject -> JsonObject(element.mapValues { scrub(it.value) })
            is JsonArray -> JsonArray(element.map(::scrub))
            is JsonPrimitive -> if (element.isString) JsonPrimitive(SecretText.redact(element.content, credentials)) else element
        }
        val encoded = scrub(json.encodeToJsonElement(safe)).toString()
        require(encoded.toByteArray(Charsets.UTF_8).size <= MAX_BYTES)
        decode(encoded) // Redacted IDs or structure must still satisfy the schema.
        return encoded
    }
    fun decode(text: String): BackupArchive {
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES)
        try {
            // Bound nesting before asking the recursive JSON parser to handle untrusted input.
            var depth = 0; var quoted = false; var escaped = false
            text.forEach { c ->
                if (quoted) { if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == '"') quoted = false }
                else when (c) { '"' -> quoted = true; '{', '[' -> { depth++; require(depth <= 64) }; '}', ']' -> depth-- }
            }
            val element = json.parseToJsonElement(text).jsonObject
            require(element["schemaVersion"]?.jsonPrimitive?.let { !it.isString && it.intOrNull == 1 } == true) { "Unsupported backup version" }
            return json.decodeFromJsonElement<BackupArchive>(element).also(::validate)
        } catch (error: Exception) {
            throw IllegalArgumentException("Invalid backup", error)
        }
    }
    fun validate(value: BackupArchive) {
        require(value.schemaVersion == 1)
        fun identifiers(values: List<String>, maximum: Int) {
            require(values.size <= maximum && values.all { id.matches(it) } && values.distinct().size == values.size)
        }
        identifiers(value.chats.map { it.id }, 1000)
        identifiers(value.projects.map { it.id }, 100)
        identifiers(value.memory.map { it.id }, 100)
        identifiers(value.personas.map { it.id }, 50)
        identifiers(value.tasks.map { it.id }, 150)
        value.chats.forEach { chat ->
            require(!chat.temporary && chat.pendingRunId == null && chat.supersededRunIds.isEmpty())
            require(chat.title.length <= 1000 && chat.createdAt >= 0 && chat.systemPrompt.length <= 8000)
            require(chat.projectId == null || id.matches(chat.projectId))
            identifiers(chat.items.map { it.id }, 10000)
            require(chat.history == ConversationBranch.completeExchanges(chat.history))
            require(chat.items.flatMap { it.steps }.none { it is TurnStep.ToolCall && it.status in setOf(ToolStatus.RUNNING, ToolStatus.WAITING) })
            require(chat.history.size <= 20000 && chat.items.all { it.role in setOf("user", "assistant") })
            chat.items.flatMap { it.artifacts }.forEach { artifact ->
                require(artifact.path.isNotBlank() && !artifact.path.startsWith('/') && !artifact.path.contains('\\'))
                require(artifact.path.split('/').none { it == ".." || it == "." || it.isBlank() })
                require(':' !in artifact.path && '\u0000' !in artifact.path)
            }
            chat.assistant?.let { validateAssistant(it) }
        }
        value.projects.forEach {
            require(it.name.isNotBlank() && it.name.length <= 100 && it.instructions.length <= 8000)
            identifiers(it.files.map { file -> file.id }, 20)
            require(it.files.all { file -> file.name.isNotBlank() && file.name.length <= 160 && file.text.length <= ProjectStore.MAX_FILE_CHARS })
            require(it.files.sumOf { file -> file.text.length } <= ProjectStore.MAX_CONTEXT_CHARS)
        }
        value.memory.forEach { require(it.text.isNotBlank() && it.text.length <= 2000) }
        value.personas.forEach(::validateAssistant)
        value.tasks.forEach {
            require(it.title.isNotBlank() && it.title.length <= 100 && it.prompt.isNotBlank() && it.prompt.length <= 16000)
            require(it.model.isNotBlank() && it.model.length <= 200 && it.runAt in 1..253402300799000L)
            require(it.intervalMinutes == 0L || it.intervalMinutes in 15..525600)
        }
        value.settings.let {
            require(it.appearance in setOf("SYSTEM", "LIGHT", "DARK") && it.fontScale.isFinite() && it.fontScale in 0.85f..1.3f)
            require(it.appLanguage in setOf("", "en", "bn") && it.audioLanguage in setOf("", "en-US", "bn-BD"))
            require(it.customInstructions.length <= 8000 && it.disabledTools.size <= 128 && it.disabledTools.all(id::matches))
            require(it.searchBackend in setOf("duckduckgo", "searxng"))
        }
    }
    private fun validateAssistant(value: SavedAssistant) {
        require(id.matches(value.id) && value.name.isNotBlank() && value.name.length <= 80 && value.instructions.length <= 8000)
        require(value.defaultTools.size <= 128 && value.defaultTools.all(id::matches))
    }
}
