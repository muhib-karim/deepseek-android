package app.dsandroid.core.agent

import org.junit.Assert.*
import org.junit.Test

class ResearchPolicyTest {
    @Test fun plansThenInvestigatesThenReportsAfterMultipleSearchAndFetchRounds() {
        assertFalse(ResearchPolicy.toolsAllowed(ResearchPhase.PLAN))
        assertEquals(ResearchPhase.INVESTIGATE, ResearchPolicy.afterAnswer(ResearchPhase.PLAN))
        val evidence = listOf(
            TurnStep.ToolCall("a", "web_search", "{\"query\":\"one\"}", ToolStatus.DONE),
            TurnStep.ToolCall("b", "web_fetch", "{\"url\":\"https://example.org/a\"}", ToolStatus.DONE),
            TurnStep.ToolCall("c", "web_search", "{\"query\":\"two\"}", ToolStatus.DONE),
            TurnStep.ToolCall("d", "web_fetch", "{\"url\":\"https://example.org/b\"}", ToolStatus.DONE))
        assertTrue(ResearchPolicy.enoughEvidence(evidence))
        assertFalse(ResearchPolicy.enoughEvidence(evidence.take(2)))
        assertFalse(ResearchPolicy.enoughEvidence(evidence.map { it.copy(status = ToolStatus.DENIED) }))
        assertFalse(ResearchPolicy.toolsAllowed(ResearchPhase.REPORT))
        assertTrue(ResearchPolicy.guidance(ResearchPhase.REPORT).contains("limitations"))
    }
    @Test fun duplicateCallsDoNotCountAsMultipleEvidenceRounds() {
        val search = TurnStep.ToolCall("a", "web_search", "{\"query\":\"same\"}", ToolStatus.DONE)
        val fetch = TurnStep.ToolCall("b", "web_fetch", "{\"url\":\"https://example.org\"}", ToolStatus.DONE)
        assertFalse(ResearchPolicy.enoughEvidence(listOf(search, search.copy(id = "c"), fetch, fetch.copy(id = "d"))))
    }
}
