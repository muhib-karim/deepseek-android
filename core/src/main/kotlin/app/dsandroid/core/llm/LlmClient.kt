package app.dsandroid.core.llm

import kotlinx.coroutines.flow.Flow

interface LlmClient {
    fun stream(messages: List<ChatMessage>, tools: List<ToolSpec>): Flow<ChatChunk>
}
