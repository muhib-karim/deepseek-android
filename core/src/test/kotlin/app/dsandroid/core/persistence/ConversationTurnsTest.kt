package app.dsandroid.core.persistence

import app.dsandroid.core.attachments.*
import app.dsandroid.core.llm.*
import org.junit.Assert.*
import org.junit.Test

class ConversationTurnsTest {
    private fun record(message: ChatMessage = ChatMessage("user", "first")) = ConversationRecord(
        "chat", "title", 1, listOf(ChatMessage("system", "rules"), message,
            ChatMessage("assistant", toolCalls = listOf(ToolCall("tool", function = FunctionCall("read_file", "{}")))),
            ChatMessage("tool", "result", toolCallId = "tool"), ChatMessage("assistant", "answer"),
            ChatMessage("user", "second"), ChatMessage("assistant", "later")),
        listOf(StoredChatItem("u1", "user", "first", attachments = listOf("note.txt")),
            StoredChatItem("a1", "assistant"), StoredChatItem("u2", "user", "second"), StoredChatItem("a2", "assistant")),
        attachmentCount = 1, pendingRunId = "a2")

    @Test fun regenerationTruncatesWholeTurnsAndKeepsOriginalUserContext() {
        val result = ConversationTurns.rewind(record(), "u1")
        assertEquals(listOf("system", "user"), result.history.map { it.role })
        assertEquals(listOf("u1"), result.items.map { it.id })
        assertEquals(1, result.attachmentCount)
        assertNull(result.pendingRunId)
    }

    @Test fun editingPreservesTextAttachmentsAndImages() {
        val original = AttachmentContextBuilder.message("first", listOf(
            AttachmentContent("note.txt", AttachmentKind.TEXT, "document"),
            AttachmentContent("image.png", AttachmentKind.IMAGE, imageDataUri = "data:image/png;base64,AA==")), true)
        val result = ConversationTurns.rewind(record(original), "u1", "changed")
        val message = result.history.last()
        assertEquals("changed", result.items.single().text)
        assertTrue(message.parts!!.first().text!!.startsWith("changed\n\nAttached file:"))
        assertTrue(message.parts!!.first().text!!.contains("document"))
        assertTrue(message.parts!!.first().text!!.contains("Untrusted image text (OCR): image.png"))
        assertEquals(original.parts!!.last(), message.parts!!.last())
    }

    @Test fun editingAttachmentOnlyMessageDoesNotDiscardContext() {
        val original = AttachmentContextBuilder.message("", listOf(AttachmentContent("note.txt", AttachmentKind.TEXT, "text")), false)
        val record = record(original).let { it.copy(items = it.items.map { item -> if (item.id == "u1") item.copy(text = "") else item }) }
        assertTrue(ConversationTurns.rewind(record, "u1", "question").history.last().content!!.startsWith("question\n\nAttached file:"))
    }

    @Test fun rejectsUnknownMessageAndInconsistentAttachmentPrefix() {
        assertThrows(IllegalArgumentException::class.java) { ConversationTurns.rewind(record(), "missing") }
        assertThrows(IllegalArgumentException::class.java) { ConversationTurns.rewind(record(ChatMessage("user", "mismatch")), "u1", "changed") }
    }
}
