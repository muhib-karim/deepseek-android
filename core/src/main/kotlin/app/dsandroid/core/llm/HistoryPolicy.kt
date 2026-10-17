package app.dsandroid.core.llm

/** DeepSeek needs reasoning within an active tool turn, never from earlier turns. */
object HistoryPolicy {
    fun forRequest(messages: List<ChatMessage>): List<ChatMessage> {
        val lastUser = messages.indexOfLast { it.role == "user" }
        return messages.mapIndexed { index, message ->
            if (index < lastUser) message.copy(reasoningContent = null) else message
        }
    }
}
