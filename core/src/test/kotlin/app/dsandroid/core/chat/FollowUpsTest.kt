package app.dsandroid.core.chat

import org.junit.Assert.*
import org.junit.Test

class FollowUpsTest {
    @Test fun parsesOnlyBoundedTrailingQuestions() {
        val parsed = FollowUps.parse("Answer\n<follow-ups>[\"Why?\",\"Example?\",\"Next?\"]</follow-ups>")
        assertEquals("Answer", parsed.answer)
        assertEquals(listOf("Why?", "Example?", "Next?"), parsed.questions)
    }
    @Test fun incompleteFooterIsHiddenOnlyWhileStreaming() {
        val text = "Answer\n<follow-ups>[\"Why?\","
        assertEquals("Answer", FollowUps.display(text, true))
        assertEquals(text, FollowUps.display(text, false))
    }
    @Test fun invalidOrQuotedBlocksRemainAnswerText() {
        listOf("Answer\n<follow-ups>[\"Why?\"]</follow-ups>", "```html\n<follow-ups>[\"Why?\",\"How?\"]</follow-ups>\n```", "Answer\n<follow-ups>[\"Why?\",\"Why?\"]</follow-ups>", "Answer\n<follow-ups>[\"Why?\",\"How?\"]</follow-ups> trailing").forEach { text ->
            val result = FollowUps.parse(text)
            assertEquals(text, result.answer)
            assertTrue(result.questions.isEmpty())
        }
    }
}
