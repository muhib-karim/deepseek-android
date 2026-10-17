package app.dsandroid.core.persistence

import app.dsandroid.core.agent.TurnStep
import app.dsandroid.core.llm.ChatMessage
import app.dsandroid.core.tools.Artifact
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ConversationStoreTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun historyAndVisibleTimelineSurviveReopeningStore() {
        val folder = temporary.newFolder()
        val record = ConversationRecord("chat-1", "First chat", 123L,
            listOf(ChatMessage("user", "Make a file"), ChatMessage("assistant", "Ready")),
            listOf(StoredChatItem("turn-1", "assistant", steps = listOf(TurnStep.Answer("Ready")),
                artifacts = listOf(Artifact("report.docx", "application/test", "report.docx")))),
            attachmentCount = 2, pendingRunId = "turn-1")
        ConversationStore(folder).save(record)
        assertEquals(listOf(record), ConversationStore(folder).loadAll())
    }

    @Test fun replacementRenameAndDeletionAreDurable() {
        val folder = temporary.newFolder()
        val store = ConversationStore(folder)
        val record = ConversationRecord("chat-1", "Before", 1L, emptyList(), emptyList())
        store.save(record)
        store.save(record.copy(title = "After"))
        assertEquals("After", ConversationStore(folder).loadAll().single().title)
        store.delete(record.id)
        assertTrue(ConversationStore(folder).loadAll().isEmpty())
    }

    @Test fun corruptChatDoesNotHideHealthyHistory() {
        val folder = temporary.newFolder()
        val store = ConversationStore(folder)
        store.save(ConversationRecord("good", "Readable", 1L, emptyList(), emptyList()))
        java.io.File(folder, "bad.json").writeText("incomplete JSON")
        assertEquals("good", store.loadAll().single().id)
    }

    @Test fun identifiersCannotEscapeDirectoryAndWritesAreBounded() {
        val files = JsonFileStore(temporary.newFolder(), maxBytes = 20)
        for (id in listOf("../outside", "/outside", "a/b", "a\\b", ".", "")) {
            try { files.write(id, "{}"); fail("Accepted unsafe id") } catch (_: IllegalArgumentException) { }
        }
        try { files.write("large", "x".repeat(21)); fail("Accepted oversized JSON") } catch (_: IllegalArgumentException) { }
        assertTrue(files.ids().isEmpty())
    }
}
