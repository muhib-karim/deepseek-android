package app.dsandroid.core.persistence

import app.dsandroid.core.llm.*
import app.dsandroid.core.personalization.ResponseStyle
import org.junit.Assert.*
import org.junit.Test

class ConversationBranchTest {
    private fun original() = ConversationRecord("original", "Topic", 1,
        listOf(ChatMessage("system", "rules"), ChatMessage("user", "first\n\nfile context"),
            ChatMessage("assistant", toolCalls = listOf(ToolCall("t", function = FunctionCall("read_file", "{}")))),
            ChatMessage("tool", "data", toolCallId = "t"), ChatMessage("assistant", "answer"),
            ChatMessage("user", "later"), ChatMessage("assistant", "later answer")),
        listOf(StoredChatItem("u1", "user", "first", listOf("a.txt")), StoredChatItem("a1", "assistant"),
            StoredChatItem("u2", "user", "later"), StoredChatItem("a2", "assistant")),
        pendingRunId = "a2", pinned = true, responseStyle = ResponseStyle.FORMAL, studyMode = true)

    @Test fun branchFromUserRetainsAttachmentContextAndDropsAllLaterActions() {
        val source = original()
        val branch = ConversationBranch.create(source, "u1", "branch", "Topic (branch)", 2)
        assertEquals(listOf("system", "user"), branch.history.map { it.role })
        assertTrue(branch.history.last().content!!.contains("file context"))
        assertEquals(1, branch.attachmentCount)
        assertNotEquals("u1", branch.items.single().id)
        assertNull(branch.pendingRunId)
        assertFalse(branch.pinned)
        assertEquals(ResponseStyle.FORMAL, branch.responseStyle)
        assertTrue(branch.studyMode)
        assertEquals(4, source.items.size)
    }
    @Test fun branchFromAssistantKeepsItsCompleteToolExchangeAndTemporaryStatus() {
        val branch = ConversationBranch.create(original().copy(temporary = true), "a1", "branch", "Branch", 3)
        assertEquals(listOf("system", "user", "assistant", "tool", "assistant"), branch.history.map { it.role })
        assertEquals(2, branch.items.size)
        assertTrue(branch.temporary)
        assertTrue(branch.supersededRunIds.isEmpty())
    }
    @Test fun incompleteToolExchangeCannotBecomeAnExecutableBranch() {
        val branch = ConversationBranch.create(original().copy(history = original().history.take(3)), "a1", "branch", "Branch", 3)
        assertEquals(listOf("system", "user"), branch.history.map { it.role })
        assertNull(branch.pendingRunId)
    }
}
