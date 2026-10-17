package app.dsandroid.data

import app.dsandroid.core.agent.RunCheckpoint
import app.dsandroid.core.llm.ChatMessage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileCheckpointStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun unfinishedRunSurvivesNewStoreInstanceAndCompletionHidesResume() = runBlocking {
        val directory = temporary.newFolder()
        val checkpoint = RunCheckpoint("turn-1", "chat-1", "model", messages = listOf(ChatMessage("user", "Continue")))
        FileCheckpointStore(directory).save(checkpoint)
        val reopened = FileCheckpointStore(directory)
        assertEquals(checkpoint, reopened.load("turn-1"))
        assertEquals(listOf(checkpoint), reopened.unfinished())
        reopened.save(checkpoint.copy(complete = true))
        assertTrue(reopened.unfinished().isEmpty())
        assertEquals(1, reopened.all().size)
        reopened.deleteConversation("chat-1")
        assertTrue(reopened.all().isEmpty())
        reopened.delete("turn-1")
        assertNull(reopened.load("turn-1"))
    }
}
