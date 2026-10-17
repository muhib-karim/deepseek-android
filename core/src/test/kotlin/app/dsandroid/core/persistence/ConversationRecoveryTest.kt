package app.dsandroid.core.persistence

import app.dsandroid.core.agent.RunCheckpoint
import app.dsandroid.core.agent.TurnStep
import app.dsandroid.core.llm.ChatMessage
import org.junit.Assert.*
import org.junit.Test

class ConversationRecoveryTest {
    @Test fun interruptedRewriteRetainsRevisedPromptBeforeNewCheckpointExists() {
        val old = ConversationRecord("chat", "Task", 1L, listOf(ChatMessage("user", "original"), ChatMessage("assistant", "reply")),
            listOf(StoredChatItem("user", "user", "original"), StoredChatItem("old", "assistant")), pendingRunId = "old")
        val revised = ConversationTurns.rewind(old, "user", "edited").let {
            it.copy(items = it.items + StoredChatItem("new", "assistant"), pendingRunId = "new")
        }
        val oldCheckpoint = RunCheckpoint("old", "chat", messages = old.history)
        val recovered = ConversationRecovery.reconcile(listOf(revised), listOf(oldCheckpoint), 2L).single()
        assertNull(recovered.pendingRunId)
        assertEquals("edited", recovered.history.last().content)
        assertEquals("edited", recovered.items.first().text)
        assertEquals(listOf("user", "new"), recovered.items.map { it.id })
    }
    @Test fun editedConversationDoesNotResurrectSupersededUnfinishedCheckpoint() {
        val record = ConversationRecord("chat", "Edited", 1L, listOf(ChatMessage("user", "new question")),
            listOf(StoredChatItem("u1", "user", "new question")), supersededRunIds = setOf("old"))
        val old = RunCheckpoint("old", "chat", messages = listOf(ChatMessage("user", "old question")))
        val recovered = ConversationRecovery.reconcile(listOf(record), listOf(old), 2L).single()
        assertEquals(record, recovered)
    }

    @Test fun checkpointSourcesSurviveRecovery() {
        val record = ConversationRecord("chat", "Research", 1L, emptyList(), emptyList())
        val sources = listOf(app.dsandroid.core.tools.WebSource(1, "Title", "https://example.org/"))
        val checkpoint = RunCheckpoint("run", "chat", sources = sources)
        assertEquals(sources, ConversationRecovery.apply(record, checkpoint).items.last().sources)
    }
    @Test fun completeCheckpointRepairsStaleConversationBeforeHidingResume() {
        val record = ConversationRecord("chat", "Task", 1L, listOf(ChatMessage("user", "Finish")),
            listOf(StoredChatItem("run", "assistant")), pendingRunId = "run")
        val checkpoint = RunCheckpoint("run", "chat", messages = record.history + ChatMessage("assistant", "Finished"),
            steps = listOf(TurnStep.Answer("Finished")), complete = true)
        val recovered = ConversationRecovery.reconcile(listOf(record), listOf(checkpoint), 2L).single()
        assertNull(recovered.pendingRunId)
        assertEquals("Finished", recovered.history.last().content)
        assertEquals(listOf(TurnStep.Answer("Finished")), recovered.items.last().steps)
    }
    @Test fun orphanCheckpointBecomesVisibleResumableConversation() {
        val checkpoint = RunCheckpoint("run", "chat", messages = listOf(ChatMessage("user", "Make a deck")))
        val record = ConversationRecovery.reconcile(emptyList(), listOf(checkpoint), 2L).single()
        assertEquals("chat", record.id)
        assertEquals("run", record.pendingRunId)
        assertTrue(record.items.any { it.role == "user" && it.text == "Make a deck" })
    }
    @Test fun missingInitialCheckpointDoesNotLeavePermanentResumeBlock() {
        val record = ConversationRecord("chat", "Task", 1L, emptyList(), emptyList(), pendingRunId = "missing")
        assertNull(ConversationRecovery.reconcile(listOf(record), emptyList(), 2L).single().pendingRunId)
    }
}
