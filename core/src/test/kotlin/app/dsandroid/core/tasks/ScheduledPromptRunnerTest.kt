package app.dsandroid.core.tasks

import app.dsandroid.core.llm.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ScheduledPromptRunnerTest {
    private val task = ScheduledTask("one", "Task", "Summarize the plan", 2000, 0, "original-account", "model")
    @Test fun changedAccountNeverConstructsOrCallsAClient() = runTest {
        var created = false
        assertTrue(runCatching { ScheduledPromptRunner.answer(task, "another-account") { created = true; error("Must not connect") } }.isFailure)
        assertFalse(created)
    }
    @Test fun runsOnlyTheApprovedPromptWithoutToolsOrPriorChatHistory() = runTest {
        var calls = 0
        val llm = object : LlmClient {
            override fun stream(messages: List<ChatMessage>, tools: List<ToolSpec>): Flow<ChatChunk> {
                calls++
                assertTrue(tools.isEmpty())
                assertEquals(listOf("system", "user"), messages.map { it.role })
                assertEquals("Summarize the plan", messages.last().content)
                return flowOf(ChatChunk(listOf(StreamChoice(delta = StreamDelta(content = "Scheduled answer")))))
            }
        }
        assertEquals("Scheduled answer", ScheduledPromptRunner.answer(task, "original-account") { llm })
        assertEquals(1, calls)
    }
    @Test fun unsolicitedActionsAndOversizedAnswersAreRejected() = runTest {
        val toolCall = ToolCallDelta(0, "call", function = FunctionDelta("open_url", "{}"))
        listOf(StreamDelta(toolCalls = listOf(toolCall)), StreamDelta(content = "x".repeat(32001))).forEach { delta ->
            val llm = object : LlmClient {
                override fun stream(messages: List<ChatMessage>, tools: List<ToolSpec>) = flowOf(ChatChunk(listOf(StreamChoice(delta = delta))))
            }
            assertTrue(runCatching { ScheduledPromptRunner.answer(task, "original-account") { llm } }.isFailure)
        }
    }
}
