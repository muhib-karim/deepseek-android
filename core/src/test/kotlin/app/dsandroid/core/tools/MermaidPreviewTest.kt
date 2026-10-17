package app.dsandroid.core.tools

import app.dsandroid.core.artifacts.*

import org.junit.Assert.*
import org.junit.Test

class MermaidPreviewTest {
    @Test fun rendersFlowchartAndSequenceWithoutInterpretingLabelsAsMarkup() {
        val flow = requireNotNull(MermaidPreview.render("flowchart LR\nA[<script>] --> B[Done]"))
        assertTrue(flow.contains("&lt;script&gt;"))
        assertFalse(flow.contains("<script>"))
        assertNotNull(MermaidPreview.render("sequenceDiagram\nparticipant A\nparticipant B\nA->>B: Hello"))
    }
    @Test fun unsupportedSyntaxFailsExplicitlyRatherThanHidingParts() {
        assertNull(MermaidPreview.render("pie\n\"One\" : 1"))
        assertNull(MermaidPreview.render("flowchart TD\nsubgraph group\nA --> B\nend"))
        assertNull(MermaidPreview.render("flowchart TD\nclick A \"https://example.com\""))
    }
}
