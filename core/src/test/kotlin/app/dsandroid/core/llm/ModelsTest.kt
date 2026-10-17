package app.dsandroid.core.llm

import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ModelsTest {
    @Test fun omitsAbsentOptionalFieldsFromUserMessages() {
        val message = LlmJson.parseToJsonElement(LlmJson.encodeToString(ChatMessage("user", "Hello"))).jsonObject
        assertFalse(message.containsKey("tool_calls"))
        assertFalse(message.containsKey("tool_call_id"))
        assertFalse(message.containsKey("reasoning_content"))
    }
    @Test fun preservesOpenAiWireNamesAndToolChoice() {
        val call = ToolCall("call1", function = FunctionCall("write", "{}"))
        val request = ChatRequest("deepseek-chat", listOf(ChatMessage("assistant", reasoningContent = "why", toolCalls = listOf(call)),
            ChatMessage("tool", "ok", toolCallId = "call1")),
            tools = listOf(ToolSpec(function = FunctionSpec("write", "Write", buildJsonObject { put("type", "object") }))),
            toolChoice = JsonPrimitive("auto"))
        val json = LlmJson.encodeToString(request)
        val objectValue = LlmJson.parseToJsonElement(json).jsonObject
        assertTrue(objectValue["stream"]!!.jsonPrimitive.boolean)
        assertEquals("auto", objectValue["tool_choice"]!!.jsonPrimitive.content)
        assertTrue(json.contains("reasoning_content"))
        assertTrue(json.contains("tool_call_id"))
        assertEquals(request, LlmJson.decodeFromString<ChatRequest>(json))
    }
}
