package app.dsandroid.core.workbench

import org.junit.Assert.*
import org.junit.Test

class ArtifactRevisionTest {
    private val fence = "```"
    private val page = WorkbenchArtifact.create("fabc12345", "chat-1", "Launch page", ArtifactKind.HTML,
        "<html><body><button>Go</button></body></html>", VersionOrigin.ANSWER, now = 0)

    @Test fun promptCarriesTheEditedSourceTheInstructionAndTheReplyFormat() {
        val edited = "<html><body><button class=\"big\">Go</button></body></html>"
        val prompt = ArtifactRevision.prompt(page, baseVersion = 2, source = edited, instruction = "  Make the button blue  ")
        assertTrue(prompt.startsWith("Please update the HTML artifact \"Launch page\" (version 2)."))
        assertTrue(prompt.contains("Change request: Make the button blue\n"))
        assertTrue(prompt.contains("in one ${fence}html code block"))
        assertTrue(prompt.endsWith("${fence}html\n$edited\n$fence"))
        assertEquals("Change “Launch page” (v2): Make the button blue", ArtifactRevision.display(page, 2, "  Make the button blue "))
    }

    @Test fun sourceWithBackticksGetsALongerFence() {
        val notes = WorkbenchArtifact.create("fabc12345", "chat-1", "Notes", ArtifactKind.MARKDOWN, "# Notes", VersionOrigin.ANSWER, now = 0)
        val source = "# Notes\n\n${fence}kotlin\nval x = 1\n$fence\n"
        val prompt = ArtifactRevision.prompt(notes, 1, source, "add a summary")
        assertTrue(prompt.endsWith("````markdown\n# Notes\n\n${fence}kotlin\nval x = 1\n$fence\n````"))
        val reply = "Done.\n\n````markdown\n# Notes\n\nSummary.\n\n${fence}kotlin\nval x = 1\n$fence\n````\n"
        assertEquals("# Notes\n\nSummary.\n\n${fence}kotlin\nval x = 1\n$fence", ArtifactRevision.extract(reply, ArtifactKind.MARKDOWN))
    }

    @Test fun rejectsEmptyOrOversizedInstructions() {
        assertTrue(runCatching { ArtifactRevision.prompt(page, 1, page.latest.source, "   ") }.isFailure)
        assertTrue(runCatching { ArtifactRevision.prompt(page, 1, page.latest.source, "x".repeat(ArtifactRevision.MAX_INSTRUCTION_CHARS + 1)) }.isFailure)
        assertTrue(runCatching { ArtifactRevision.prompt(page, 1, "", "make it blue") }.isFailure)
    }

    @Test fun extractTakesTheLastBlockOfTheArtifactsKind() {
        val reply = "Old:\n${fence}css\nbutton { color: red }\n$fence\n\nNew page:\n${fence}html\n<p>v1</p>\n$fence\n\nFinal:\n${fence}html\n<p>v2</p>\n$fence\nThe button is blue now."
        assertEquals("<p>v2</p>", ArtifactRevision.extract(reply, ArtifactKind.HTML))
        assertNull(ArtifactRevision.extract(reply, ArtifactKind.SVG))
        assertEquals("<svg viewBox=\"0 0 4 4\"/>", ArtifactRevision.extract("${fence}xml\n<svg viewBox=\"0 0 4 4\"/>\n$fence", ArtifactKind.SVG))
    }

    @Test fun anUntaggedBlockIsAcceptedOnlyWhenItLooksLikeTheKind() {
        assertEquals("<!doctype html>\n<p>hi</p>", ArtifactRevision.extract("$fence\n<!doctype html>\n<p>hi</p>\n$fence", ArtifactKind.HTML))
        assertNull(ArtifactRevision.extract("$fence\nprint('hi')\n$fence", ArtifactKind.HTML))
        assertEquals("<svg/>", ArtifactRevision.extract("$fence\n<svg/>\n$fence", ArtifactKind.SVG))
        assertNull(ArtifactRevision.extract("${fence}html\n<p>unfinished", ArtifactKind.HTML))
        assertNull(ArtifactRevision.extract("${fence}html\n   \n$fence", ArtifactKind.HTML))
    }
}
