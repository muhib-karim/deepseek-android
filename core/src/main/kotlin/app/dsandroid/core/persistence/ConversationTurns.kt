package app.dsandroid.core.persistence

import app.dsandroid.core.llm.ChatMessage

/** A replacement drops the entire suffix, including tool messages and later turns. */
object ConversationTurns {
    fun rewind(record: ConversationRecord, userId: String, replacement: String? = null): ConversationRecord {
        val itemIndex = record.items.indexOfFirst { it.id == userId && it.role == "user" }
        require(itemIndex >= 0) { "Unknown user message" }
        val ordinal = record.items.take(itemIndex).count { it.role == "user" }
        val historyIndex = record.history.indices.filter { record.history[it].role == "user" }.getOrNull(ordinal)
        require(historyIndex != null) { "Missing user context" }
        val item = record.items[itemIndex]
        val message = record.history[historyIndex]
        val nextText = replacement?.trim()
        val nextMessage = if (nextText == null) message else replacePrompt(message, item.text, nextText)
        val items = record.items.take(itemIndex) + item.copy(text = nextText ?: item.text)
        return record.copy(history = record.history.take(historyIndex) + nextMessage, items = items,
            attachmentCount = items.sumOf { it.attachments.size }, pendingRunId = null,
            supersededRunIds = record.supersededRunIds + record.items.drop(itemIndex + 1).filter { it.role == "assistant" }.map { it.id })
    }

    private fun replacePrompt(message: ChatMessage, old: String, replacement: String): ChatMessage {
        fun replace(text: String): String {
            val suffix = when {
                old.isEmpty() -> text
                text == old -> ""
                text.startsWith("$old\n\n") -> text.substring(old.length + 2)
                else -> throw IllegalArgumentException("Stored prompt does not match its context")
            }
            return listOf(replacement, suffix).filter { it.isNotEmpty() }.joinToString("\n\n")
        }
        val parts = message.parts
        if (parts == null) return message.copy(content = replace(message.content.orEmpty()))
        require(parts.firstOrNull()?.type == "text") { "Missing text context" }
        return message.copy(parts = listOf(parts.first().copy(text = replace(parts.first().text.orEmpty()))) + parts.drop(1))
    }
}
