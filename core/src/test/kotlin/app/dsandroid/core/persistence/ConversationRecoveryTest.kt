package app.dsandroid.core.persistence

import app.dsandroid.core.agent.RunCheckpoint
import app.dsandroid.core.agent.TurnStep
import app.dsandroid.core.llm.ChatMessage
import org.junit.Assert.*
import org.junit.Test

class ConversationRecoveryTest {
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
