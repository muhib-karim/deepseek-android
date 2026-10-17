package app.dsandroid.core.llm

import kotlinx.coroutines.flow.Flow

interface LlmClient {
    suspend fun listModels(): List<ModelInfo> = emptyList()
    fun stream(messages: List<ChatMessage>, tools: List<ToolSpec>): Flow<ChatChunk>
}
