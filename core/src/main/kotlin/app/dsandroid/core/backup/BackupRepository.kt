package app.dsandroid.core.backup

import app.dsandroid.core.assistants.AssistantStore
import app.dsandroid.core.persistence.ConversationStore
import app.dsandroid.core.personalization.*
import app.dsandroid.core.tasks.*
import java.io.File

/** Explicit typed stores only: never walk directories containing settings, tokens or checkpoints. */
class BackupRepository(private val root: File) {
    private val chats = ConversationStore(File(root, "conversations"))
    private val projects = ProjectStore(File(root, "projects"))
    private val memory = MemoryStore(File(root, "memory"))
    private val personas = AssistantStore(File(root, "assistants"))
    private val tasks = TaskStore(File(root, "tasks"))
    fun snapshot(settings: BackupSettings): BackupArchive = BackupArchive(
        chats = chats.loadAll(), projects = projects.all(), memory = memory.all(), personas = personas.all(),
        tasks = tasks.tasks().map { BackupTask(it.id, it.title, it.prompt, it.runAt, it.intervalMinutes, it.model) }, settings = settings)

    /** Merge only: existing IDs win; retries are idempotent, imported schedules never run. */
    fun merge(value: BackupArchive) {
        BackupCodec.validate(value)
        val chatIds = chats.loadAll().map { it.id }.toSet()
        val projectIds = projects.all().map { it.id }.toSet()
        val personaIds = personas.all().map { it.id }.toSet()
        val newChats = value.chats.filterNot { it.id in chatIds }
        val newProjects = value.projects.filterNot { it.id in projectIds }
        val newPersonas = value.personas.filterNot { it.id in personaIds }
        require(personaIds.size + newPersonas.size <= 50)
        val facts = (memory.all() + value.memory).distinctBy { it.id }
        require(facts.size <= 100)
        val definitions = value.tasks.map { ScheduledTask(it.id, it.title, it.prompt, it.runAt, it.intervalMinutes,
            providerKey = "restored-unbound", model = it.model, active = false) }
        require((tasks.tasks() + definitions).distinctBy { it.id }.size <= 150)
        // Exercise actual store limits in a disposable private staging directory before
        // touching live records. Malformed/oversized input cannot cause partial writes.
        val staging = File(root, "backup-staging")
        check(!staging.exists() || staging.deleteRecursively())
        check(staging.mkdirs())
        try {
            newChats.forEach { ConversationStore(File(staging, "conversations")).save(it) }
            newProjects.forEach { ProjectStore(File(staging, "projects")).save(it) }
            newPersonas.forEach { AssistantStore(File(staging, "assistants")).save(it) }
            MemoryStore(File(staging, "memory")).merge(facts)
            TaskStore(File(staging, "tasks")).mergeDefinitions(definitions)
            newChats.forEach(chats::save)
            newProjects.forEach(projects::save)
            newPersonas.forEach(personas::save)
            memory.merge(value.memory)
            tasks.mergeDefinitions(definitions)
        } finally { staging.deleteRecursively() }
    }
}
