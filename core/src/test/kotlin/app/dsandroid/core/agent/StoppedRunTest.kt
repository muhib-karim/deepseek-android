package app.dsandroid.core.agent

import app.dsandroid.core.llm.*
import org.junit.Assert.*
import org.junit.Test

class StoppedRunTest {
    @Test fun stopClosesPendingBatchWithoutReplayingCompletedTools() {
        val calls = listOf("one", "two").map { ToolCall(it, function = FunctionCall("web_fetch", "{}")) }
        val assistant = ChatMessage("assistant", toolCalls = calls)
        val checkpoint = RunCheckpoint("run", messages = listOf(ChatMessage("user", "question"), assistant,
            ChatMessage("tool", "completed", toolCallId = "one")), pendingAssistant = assistant, pendingToolIndex = 1,
            steps = listOf(TurnStep.ToolCall("one", "web_fetch", "{}", ToolStatus.DONE)))
        val result = StoppedRun.finish(checkpoint, checkpoint.steps + TurnStep.ToolCall("two", "web_fetch", "{}", ToolStatus.WAITING))
        assertTrue(result.complete)
        assertNull(result.pendingAssistant)
        assertEquals(listOf("one", "two"), result.messages.filter { it.role == "tool" }.map { it.toolCallId })
        assertEquals("completed", result.messages[2].content)
        assertEquals(ToolStatus.STOPPED, (result.steps.last() as TurnStep.ToolCall).status)
    }

    @Test fun stopKeepsPartialAnswerAndEndsThinking() {
        val checkpoint = RunCheckpoint("run", messages = listOf(ChatMessage("user", "question")))
        val stopped = StoppedRun.finish(checkpoint, listOf(TurnStep.Reasoning("thought"), TurnStep.Answer("partial")))
        assertTrue((stopped.steps.first() as TurnStep.Reasoning).complete)
        assertEquals("partial", stopped.messages.last().content)
        assertEquals("assistant", stopped.messages.last().role)
    }
}
