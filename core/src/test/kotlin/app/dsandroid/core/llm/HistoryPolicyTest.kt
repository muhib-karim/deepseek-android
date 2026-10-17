package app.dsandroid.core.llm

import org.junit.Assert.*
import org.junit.Test

class HistoryPolicyTest {
    @Test fun removesOldReasoningButKeepsActiveToolTurnReasoning() {
        val messages = listOf(ChatMessage("user", "old"), ChatMessage("assistant", "old answer", "old thought"),
            ChatMessage("user", "new"), ChatMessage("assistant", reasoningContent = "active thought",
                toolCalls = listOf(ToolCall("call", function = FunctionCall("web_search", "{}")))),
            ChatMessage("tool", "result", toolCallId = "call"))
        val prepared = HistoryPolicy.forRequest(messages)
        assertNull(prepared[1].reasoningContent)
        assertEquals("active thought", prepared[3].reasoningContent)
        assertEquals("old thought", messages[1].reasoningContent)
    }
}
