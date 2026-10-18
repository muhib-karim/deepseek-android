package app.dsandroid.core.tasks

import app.dsandroid.core.llm.LlmJson
import app.dsandroid.core.persistence.JsonFileStore
import app.dsandroid.core.tools.*
import app.dsandroid.core.browser.PreviewTool
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

@Serializable data class ScheduledTask(
    val id: String, val title: String, val prompt: String, val runAt: Long, val intervalMinutes: Long,
    val providerKey: String, val model: String, val active: Boolean = true,
) {
    fun validate(now: Long) {
        require(id.matches(Regex("[A-Za-z0-9_-]{1,100}")) && title.isNotBlank() && title.length <= 100)
        require(prompt.isNotBlank() && prompt.length <= 16000 && model.isNotBlank() && model.length <= 200 && providerKey.isNotBlank() && providerKey.length <= 4096)
        require(runAt > now && runAt - now <= 366L * 24 * 60 * 60 * 1000)
        require(intervalMinutes == 0L || intervalMinutes in 15..525600)
    }
}
@Serializable data class TaskResult(val id: String, val taskId: String, val title: String, val answer: String,
    val completedAt: Long, val error: Boolean = false, val read: Boolean = false)
@Serializable private data class TaskJournal(val tasks: List<ScheduledTask> = emptyList(), val results: List<TaskResult> = emptyList(), val claims: Set<String> = emptySet())

/** Shared in-process lock covers foreground UI and WorkManager instances; one atomic journal. */
class TaskStore(directory: File) {
    private val files = JsonFileStore(directory, 8 * 1024 * 1024)
    private fun journal() = files.read("tasks")?.let { LlmJson.decodeFromString<TaskJournal>(it) } ?: TaskJournal()
    private fun write(value: TaskJournal) {
        var retained = value
        var json = LlmJson.encodeToString(retained)
        // Unicode and JSON escapes can exceed a character-count estimate. Keep active
        // schedules and the newest result, pruning older inbox/archive data by bytes.
        while (json.toByteArray(Charsets.UTF_8).size > 8 * 1024 * 1024) {
            val inactive = retained.tasks.indexOfFirst { !it.active }
            retained = when {
                inactive >= 0 -> retained.copy(tasks = retained.tasks.filterIndexed { index, _ -> index != inactive })
                retained.results.size > 1 -> retained.copy(results = retained.results.drop(1))
                else -> error("Task journal exceeds storage limit")
            }
            json = LlmJson.encodeToString(retained)
        }
        files.write("tasks", json)
        revision.update { it + 1 }
    }
    fun tasks(): List<ScheduledTask> = synchronized(lock) { journal().tasks }
    fun results(): List<TaskResult> = synchronized(lock) { journal().results.sortedByDescending { it.completedAt } }
    fun unread() = results().count { !it.read }
    fun save(task: ScheduledTask, now: Long) = synchronized(lock) {
        task.validate(now)
        val data = journal(); require(data.tasks.count { it.active && it.id != task.id } < 50)
        val retained = data.tasks.filter { it.active && it.id != task.id } + data.tasks.filterNot { it.active || it.id == task.id }.takeLast(100)
        write(data.copy(tasks = retained + task))
    }
    fun mergeDefinitions(incoming: List<ScheduledTask>) = synchronized(lock) {
        require(incoming.all { !it.active && it.providerKey == "restored-unbound" })
        incoming.forEach { require(it.runAt > 0); it.validate(it.runAt - 1) }
        val data = journal()
        val merged = (data.tasks + incoming).distinctBy { it.id }
        require(merged.size <= 150)
        // No pruning during restore: a storage failure must be visible, never drop tasks.
        val encoded = LlmJson.encodeToString(data.copy(tasks = merged))
        files.write("tasks", encoded)
        revision.update { it + 1 }
    }
    fun cancel(id: String) = synchronized(lock) {
        val data = journal(); write(data.copy(tasks = data.tasks.map { if (it.id == id) it.copy(active = false) else it }))
    }
    fun claim(task: String, run: String): Boolean = synchronized(lock) {
        require(run.matches(Regex("[A-Za-z0-9_-]{1,160}")))
        val data = journal()
        if (data.tasks.none { it.id == task && it.active } || run in data.claims) false
        else { write(data.copy(claims = (data.claims + run).toList().takeLast(1000).toSet())); true }
    }
    fun complete(result: TaskResult) = synchronized(lock) {
        val data = journal()
        val bounded = result.copy(answer = result.answer.take(32000))
        write(data.copy(results = (data.results.filterNot { it.id == result.id } + bounded).takeLast(100),
            tasks = data.tasks.map { if (it.id == result.taskId && it.intervalMinutes == 0L) it.copy(active = false) else it }))
    }
    fun markRead(id: String) = synchronized(lock) {
        val data = journal(); write(data.copy(results = data.results.map { if (it.id == id) it.copy(read = true) else it }))
    }
    companion object {
        private val lock = Any()
        private val revision = MutableStateFlow(0L)
        val changes = revision.asStateFlow()
    }
}

class SchedulePromptTool(private val providerKey: String, private val model: String,
    private val schedule: suspend (ScheduledTask) -> Unit, private val now: () -> Long = System::currentTimeMillis) : PreviewTool {
    override val name = "schedule_prompt"
    override val description get() = "Schedule a prompt for later or repeatedly. Always asks first. Background runs use the original account/model, no tools, no device actions or notifications; results go to Tasks inbox. Times are epoch milliseconds, repeat interval is 0 (once) or at least 15 minutes. Current UTC time: ${java.time.Instant.ofEpochMilli(now())}."
    override val risk = Risk.CONFIRM
    override val kind = ToolKind.ACTION
    override val schema = objectSchema(mapOf("title" to stringSchema("Task title"), "prompt" to stringSchema("Self-contained prompt"),
        "runAt" to buildJsonObject { put("type", "integer") }, "intervalMinutes" to buildJsonObject { put("type", "integer"); put("minimum", 0) }))
    private fun task(args: JsonObject): ScheduledTask {
        require(args.keys == setOf("title", "prompt", "runAt", "intervalMinutes"))
        return ScheduledTask(UUID.randomUUID().toString(), args.stringArg("title"), args.stringArg("prompt"),
            args["runAt"]!!.jsonPrimitive.long, args["intervalMinutes"]!!.jsonPrimitive.long, providerKey, model).also { it.validate(now()) }
    }
    override suspend fun confirmationArguments(args: JsonObject, ctx: ToolContext): JsonObject {
        val task = task(args)
        return buildJsonObject { args.forEach { (key, value) -> put(key, value) }; put("time", java.time.Instant.ofEpochMilli(task.runAt).toString()); put("model", model); put("account", "Current account only; never switches automatically") }
    }
    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        schedule(task(args))
        return ToolResult("Scheduled. Background execution is inexact, tool-free and uses the bound account/model. Read results in Tasks inbox.")
    }
}
