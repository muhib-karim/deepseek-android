package app.dsandroid.core.llm

import org.junit.Assert.*
import org.junit.Test

class StreamAccumulatorTest {
    @Test fun mergesContentReasoningAndInterleavedToolArgumentsByIndex() {
        val accumulator = StreamAccumulator()
        accumulator.accept(ChatChunk(listOf(StreamChoice(delta = StreamDelta(content = "Hi", reasoningContent = "Think",
            toolCalls = listOf(ToolCallDelta(1, "b", function = FunctionDelta("create_csv", "{")),
                ToolCallDelta(0, "a", function = FunctionDelta("create_markdown", "{\"content\":"))))))))
        accumulator.accept(ChatChunk(listOf(StreamChoice(delta = StreamDelta(content = "!", reasoningContent = "ing",
            toolCalls = listOf(ToolCallDelta(0, function = FunctionDelta(arguments = "\"ok\"}")),
                ToolCallDelta(1, function = FunctionDelta(arguments = "}"))))))))
        val message = accumulator.message()
        assertEquals("assistant", message.role)
        assertEquals("Hi!", message.content)
        assertEquals("Thinking", message.reasoningContent)
        assertEquals(listOf("a", "b"), message.toolCalls!!.map { it.id })
        assertEquals("{\"content\":\"ok\"}", message.toolCalls!![0].function.arguments)
        assertEquals("{}", message.toolCalls!![1].function.arguments)
    }
    @Test fun rejectsIncompleteToolCall() {
        val accumulator = StreamAccumulator()
        accumulator.accept(ChatChunk(listOf(StreamChoice(delta = StreamDelta(toolCalls = listOf(ToolCallDelta(0)))))))
        assertThrows(IllegalArgumentException::class.java) { accumulator.message() }
    }
    @Test fun ignoresOtherChoices() {
        val accumulator = StreamAccumulator()
        accumulator.accept(ChatChunk(listOf(StreamChoice(index = 1, delta = StreamDelta(content = "other")))))
        assertNull(accumulator.message().content)
    }
}
