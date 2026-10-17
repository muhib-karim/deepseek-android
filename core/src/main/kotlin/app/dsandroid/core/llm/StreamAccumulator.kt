package app.dsandroid.core.llm

class StreamAccumulator {
    private val content = StringBuilder()
    private val reasoning = StringBuilder()
    private val calls = sortedMapOf<Int, PendingCall>()
    private var totalChars = 0

    private class PendingCall {
        val id = StringBuilder()
        val name = StringBuilder()
        val arguments = StringBuilder()
    }

    fun accept(chunk: ChatChunk) {
        chunk.choices.filter { it.index == 0 }.forEach { choice ->
            val delta = choice.delta
            delta.content?.let { append(content, it) }
            delta.reasoningContent?.let { append(reasoning, it) }
            delta.toolCalls.orEmpty().forEach { part ->
                require(part.index in 0..63) { "Too many tool calls" }
                require(part.type == null || part.type == "function") { "Unsupported tool call type" }
                val pending = calls.getOrPut(part.index) { PendingCall() }
                part.id?.let { append(pending.id, it) }
                part.function?.name?.let { append(pending.name, it) }
                part.function?.arguments?.let { append(pending.arguments, it) }
            }
        }
    }

    private fun append(target: StringBuilder, value: String) {
        totalChars += value.length
        require(totalChars <= 2 * 1024 * 1024) { "Assistant turn too large" }
        target.append(value)
    }

    fun message(): ChatMessage {
        val toolCalls = calls.values.map {
            require(it.id.isNotEmpty() && it.name.isNotEmpty()) { "Incomplete tool call" }
            ToolCall(it.id.toString(), function = FunctionCall(it.name.toString(), it.arguments.toString()))
        }
        require(toolCalls.map { it.id }.distinct().size == toolCalls.size) { "Duplicate tool call ID" }
        return ChatMessage("assistant", content.toString().ifEmpty { null },
            reasoning.toString().ifEmpty { null }, toolCalls.ifEmpty { null })
    }
}
