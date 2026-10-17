package app.dsandroid.core.export

import app.dsandroid.core.agent.TurnStep
import app.dsandroid.core.persistence.*
import app.dsandroid.core.tools.WebSource
import org.junit.Assert.*
import org.junit.Test

class ConversationExportTest {
    private val labels = ExportLabels("You", "Assistant", "Sources")
    private fun record() = ConversationRecord("chat", "Research <script>", 0, emptyList(), listOf(
        StoredChatItem("user", "user", "Question"),
        StoredChatItem("answer", "assistant", steps = listOf(TurnStep.Reasoning("private thought"), TurnStep.Answer("## Findings\nEvidence [1]\n<script>alert(1)</script>")),
            sources = listOf(WebSource(1, "Reference", "https://example.org/")))
    ))
    @Test fun transcriptIncludesSourcesButNotReasoning() {
        val md = ConversationExporter.markdown(record(), labels)
        assertTrue(md.contains("https://example.org/"))
        assertTrue(md.contains("[1]"))
        assertFalse(md.contains("private thought"))
    }
    @Test fun reportExportsOnlyTheSelectedAnswerAndHtmlIsInert() {
        val md = ConversationExporter.markdown(record(), labels, "answer")
        assertFalse(md.contains("Question"))
        val html = ConversationExporter.html(md, "bn")
        assertTrue(html.contains("lang=\"bn\""))
        assertFalse(html.contains("<script>"))
        assertTrue(html.contains("&lt;script&gt;"))
        assertTrue(html.contains("default-src 'none'"))
        assertThrows(IllegalArgumentException::class.java) { ConversationExporter.markdown(record(), labels, "missing") }
    }
}
