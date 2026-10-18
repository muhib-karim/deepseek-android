package app.dsandroid.core.backup

import app.dsandroid.core.assistants.SavedAssistant
import app.dsandroid.core.llm.ChatMessage
import app.dsandroid.core.persistence.*
import app.dsandroid.core.personalization.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class BackupTest {
    private fun sample() = BackupArchive(chats = listOf(ConversationRecord("chat-1", "Chat", 12,
        listOf(ChatMessage("user", "Hello")), listOf(StoredChatItem("item-1", "user", "Hello")))),
        projects = listOf(ProjectRecord("project-1", "Project")), memory = listOf(SavedFact("fact-1", "Use metric units")),
        personas = listOf(SavedAssistant("persona-1", "Tutor")),
        tasks = listOf(BackupTask("task-1", "Review", "Review notes", 1234, 0, "deepseek-chat")),
        settings = BackupSettings(appearance = "DARK", appLanguage = "bn"))

    @Test fun normalUuidIdentifiersSurviveRedaction() {
        val id = "11111111-2222-4333-8444-555555555555"
        val archive = sample().copy(memory = listOf(SavedFact(id, "Use metric units")))
        assertEquals(id, BackupCodec.decode(BackupCodec.encode(archive)).memory.single().id)
    }
    @Test fun roundTripPreservesEverySection() {
        assertEquals(sample(), BackupCodec.decode(BackupCodec.encode(sample())))
    }
    @Test fun exportExcludesTemporaryChatsAndRunCheckpoints() {
        val chat = sample().chats.single()
        val result = BackupCodec.decode(BackupCodec.encode(sample().copy(chats = listOf(
            chat.copy(pendingRunId = "run-1", supersededRunIds = setOf("run-0")), chat.copy(id = "temp", temporary = true)))))
        assertEquals(1, result.chats.size)
        assertNull(result.chats.single().pendingRunId)
        assertTrue(result.chats.single().supersededRunIds.isEmpty())
    }
    @Test fun backupClosesUnfinishedTimelineAndDropsUnpairedToolExchanges() {
        val call = app.dsandroid.core.llm.ToolCall("call-1", function = app.dsandroid.core.llm.FunctionCall("read_file", "{}"))
        val step = app.dsandroid.core.agent.TurnStep.ToolCall("call-1", "read_file", "{}")
        val chat = sample().chats.single().copy(history = listOf(ChatMessage("user", "Hello"),
            ChatMessage("assistant", toolCalls = listOf(call))),
            items = listOf(StoredChatItem("item-1", "assistant", steps = listOf(step))), pendingRunId = "run-1")
        val restored = BackupCodec.decode(BackupCodec.encode(sample().copy(chats = listOf(chat)))).chats.single()
        assertEquals(listOf("user"), restored.history.map { it.role })
        assertEquals(app.dsandroid.core.agent.ToolStatus.STOPPED,
            (restored.items.single().steps.single() as app.dsandroid.core.agent.TurnStep.ToolCall).status)
        assertNull(restored.pendingRunId)
    }
    @Test fun knownCredentialsAndRecognizableSecretsAreRemovedFromText() {
        val value = sample().copy(memory = listOf(SavedFact("fact-1", "private-fixture-value Authorization: Bearer fixture-bearer")))
        val encoded = BackupCodec.encode(value, setOf("private-fixture-value"))
        assertFalse(encoded.contains("private-fixture-value"))
        assertFalse(encoded.contains("fixture-bearer"))
    }
    @Test fun unknownSecretFieldsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { BackupCodec.decode("""{"schemaVersion":1,"apiKey":"fixture"}""") }
    }
    @Test fun unsupportedMissingAndMalformedSchemasAreRejected() {
        listOf("{}", "{", "[]", "null", "{\"schemaVersion\":2}", "{\"schemaVersion\":1,\"chats\":null}").forEach {
            assertThrows(IllegalArgumentException::class.java) { BackupCodec.decode(it) }
        }
    }
    @Test fun traversalAndDuplicateIdentifiersAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { BackupCodec.encode(sample().copy(memory = listOf(SavedFact("../escape", "x")))) }
        assertThrows(IllegalArgumentException::class.java) { BackupCodec.encode(sample().copy(projects = sample().projects + sample().projects)) }
    }
    @Test fun invalidSettingsAndOversizedFilesAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { BackupCodec.encode(sample().copy(settings = BackupSettings(fontScale = 9f))) }
        assertThrows(IllegalArgumentException::class.java) { BackupCodec.read("x".repeat(BackupCodec.MAX_BYTES + 1).byteInputStream()) }
    }
    @Test fun mergeKeepsExistingRecordsAndDoesNotActivateTasks() {
        val root = Files.createTempDirectory("backup-test").toFile()
        try {
            val repository = BackupRepository(root)
            ConversationStore(root.resolve("conversations")).save(sample().chats.single().copy(title = "Existing"))
            repository.merge(sample())
            repository.merge(sample())
            assertEquals("Existing", ConversationStore(root.resolve("conversations")).loadAll().single().title)
            assertEquals("Use metric units", MemoryStore(root.resolve("memory")).all().single().text)
            val task = app.dsandroid.core.tasks.TaskStore(root.resolve("tasks")).tasks().single()
            assertFalse(task.active)
            assertEquals("restored-unbound", task.providerKey)
        } finally { root.deleteRecursively() }
    }
    @Test fun invalidArchiveMakesNoChanges() {
        val root = Files.createTempDirectory("backup-test").toFile()
        try {
            assertThrows(IllegalArgumentException::class.java) { BackupRepository(root).merge(sample().copy(
                personas = listOf(SavedAssistant("invalid", "")))) }
            assertTrue(ConversationStore(root.resolve("conversations")).loadAll().isEmpty())
        } finally { root.deleteRecursively() }
    }
}
