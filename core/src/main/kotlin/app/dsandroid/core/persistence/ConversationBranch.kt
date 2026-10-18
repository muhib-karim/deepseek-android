package app.dsandroid.core.persistence

import app.dsandroid.core.agent.TurnStep
import app.dsandroid.core.agent.ToolStatus
import app.dsandroid.core.llm.ChatMessage

/** Branches copy a transcript prefix, never checkpoints or permission grants. */
object ConversationBranch {
    fun create(source: ConversationRecord, itemId: String, id: String, title: String, now: Long): ConversationRecord {
        require(id != source.id && id.matches(Regex("[A-Za-z0-9_-]{1,60}")))
        val index = source.items.indexOfFirst { it.id == itemId }
        require(index >= 0)
        val items = source.items.take(index + 1)
        val ordinal = items.count { it.role == "user" } - 1
        val userPositions = source.history.indices.filter { source.history[it].role == "user" }
        val userIndex = requireNotNull(userPositions.getOrNull(ordinal))
        val end = if (items.last().role == "user") userIndex + 1 else userPositions.getOrNull(ordinal + 1) ?: source.history.size
        val history = completeExchanges(source.history.take(end))
        return source.copy(id = id, title = title.take(100), createdAt = now, history = history,
            items = items.mapIndexed { i, item -> item.copy(id = "$id-$i", steps = item.steps.map { step ->
                if (step is TurnStep.ToolCall && step.status in setOf(ToolStatus.RUNNING, ToolStatus.WAITING)) step.copy(status = ToolStatus.STOPPED) else step
            }) }, attachmentCount = items.sumOf { it.attachments.size }, pendingRunId = null,
            supersededRunIds = emptySet(), pinned = false)
    }
    private fun completeExchanges(messages: List<ChatMessage>): List<ChatMessage> {
        val result = mutableListOf<ChatMessage>()
        var index = 0
        while (index < messages.size) {
            val message = messages[index++]
            val calls = message.toolCalls.orEmpty()
            if (message.role == "assistant" && calls.isNotEmpty()) {
                val replies = messages.drop(index).takeWhile { it.role == "tool" }
                if (replies.size == calls.size && replies.map { it.toolCallId }.toSet() == calls.map { it.id }.toSet()) {
                    result += message; result += replies
                }
                index += replies.size
            } else if (message.role != "tool") result += message
        }
        return result
    }
}
