package app.dsandroid.core.personalization

import org.junit.Assert.*
import org.junit.Test

class ResponseStyleTest {
    @Test fun styleAndStudyApplyAlongsideUserInstructions() {
        val prompt = PromptComposer.compose("Use Bangla", "Algebra", null, emptyList(), false, ResponseStyle.CONCISE, true)
        assertTrue(prompt.contains("Use Bangla"))
        assertTrue(prompt.contains("Be concise"))
        assertTrue(prompt.contains("guiding question"))
        assertTrue(prompt.contains("untrusted"))
    }
    @Test fun styleRoundTripsAndOldChatsUseDefaults() {
        val old = """{"id":"old","title":"Title","createdAt":1,"history":[],"items":[]}"""
        val decoded = app.dsandroid.core.llm.LlmJson.decodeFromString<app.dsandroid.core.persistence.ConversationRecord>(old)
        assertEquals(ResponseStyle.DEFAULT, decoded.responseStyle)
        assertFalse(decoded.studyMode)
        val updated = decoded.copy(responseStyle = ResponseStyle.EXPLANATORY, studyMode = true)
        val encoded = app.dsandroid.core.llm.LlmJson.encodeToString(app.dsandroid.core.persistence.ConversationRecord.serializer(), updated)
        assertEquals(updated, app.dsandroid.core.llm.LlmJson.decodeFromString<app.dsandroid.core.persistence.ConversationRecord>(encoded))
    }
    @Test fun defaultDoesNotImposeTutoring() {
        assertFalse(PromptComposer.compose("", "", null, emptyList(), false).contains("guiding question"))
    }
}
