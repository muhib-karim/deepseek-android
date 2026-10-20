package app.dsandroid.core.workbench

import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class WorkbenchArtifactTest {
    private val page = "<!doctype html>\n<html>\n<head><title>Launch page</title></head>\n<body>\n<button>Go</button>\n</body>\n</html>"

    @Test fun kindsComeFromFenceLanguagesFileNamesAndSvgSniffing() {
        assertEquals(ArtifactKind.HTML, ArtifactKind.fromFence("html", page))
        assertEquals(ArtifactKind.HTML, ArtifactKind.fromFence("HTM", page))
        assertEquals(ArtifactKind.SVG, ArtifactKind.fromFence("svg", "<svg/>"))
        assertEquals(ArtifactKind.SVG, ArtifactKind.fromFence("xml", "<?xml version=\"1.0\"?>\n<!-- chart -->\n<svg viewBox=\"0 0 10 10\"></svg>"))
        assertNull(ArtifactKind.fromFence("xml", "<note><to>A</to></note>"))
        assertEquals(ArtifactKind.MARKDOWN, ArtifactKind.fromFence("md", "# Title"))
        assertEquals(ArtifactKind.MARKDOWN, ArtifactKind.fromFence("markdown title=x", "# Title"))
        assertNull(ArtifactKind.fromFence("kotlin", "fun main() {}"))
        assertEquals(ArtifactKind.HTML, ArtifactKind.fromFileName("site/index.HTML"))
        assertEquals(ArtifactKind.SVG, ArtifactKind.fromFileName("chart.svg"))
        assertEquals(ArtifactKind.MARKDOWN, ArtifactKind.fromFileName("notes.markdown"))
        assertNull(ArtifactKind.fromFileName("report.pdf"))
    }

    @Test fun titlesComeFromTheSource() {
        assertEquals("Launch page", ArtifactTitles.guess(ArtifactKind.HTML, page))
        assertEquals("Plan for Q3", ArtifactTitles.guess(ArtifactKind.MARKDOWN, "Intro\n\n## Plan for **Q3**\n"))
        assertEquals("SVG image", ArtifactTitles.guess(ArtifactKind.SVG, "<svg/>"))
        assertEquals("HTML page", ArtifactTitles.guess(ArtifactKind.HTML, "<p>hi</p>"))
        assertEquals(80, ArtifactTitles.guess(ArtifactKind.MARKDOWN, "# " + "x".repeat(300)).length)
    }

    @Test fun versionsAppendInOrderAndSkipUnchangedSource() {
        val v1 = WorkbenchArtifact.create("fabc12345", "chat-1", "Launch page", ArtifactKind.HTML, page, VersionOrigin.ANSWER, now = 1000)
        assertEquals(1, v1.latest.number)
        assertSame(v1, v1.withVersion(page, VersionOrigin.EDIT, now = 2000))
        val v2 = v1.withVersion(page.replace("Go", "Start"), VersionOrigin.EDIT, now = 2000)
        val v3 = v2.withVersion(page.replace("Go", "Begin").replace("</body>", "<p>New</p>\n</body>"), VersionOrigin.DEEPSEEK, now = 3000, note = "make it friendlier")
        assertEquals(listOf(1, 2, 3), v3.versions.map { it.number })
        assertEquals(VersionOrigin.DEEPSEEK, v3.latest.origin)
        assertEquals("make it friendlier", v3.latest.note)
        assertNull(v3.changeFromPrevious(1))
        assertEquals(DiffStats(1, 1), v3.changeFromPrevious(2))
        assertEquals("+2 -1 lines", v3.changeFromPrevious(3)!!.summary())
        assertEquals(page, v3.version(1)!!.source)
        assertNull(v3.version(9))
    }

    @Test fun historyKeepsTheOriginalAndTheNewestVersions() {
        var artifact = WorkbenchArtifact.create("fabc12345", "chat-1", "Counter", ArtifactKind.MARKDOWN, "count 0", VersionOrigin.ANSWER, now = 0)
        for (index in 1..40) artifact = artifact.withVersion("count $index", VersionOrigin.EDIT, now = index.toLong())
        assertEquals(WorkbenchArtifact.MAX_VERSIONS, artifact.versions.size)
        assertEquals(1, artifact.versions.first().number)
        assertEquals(41, artifact.latest.number)
        assertEquals((13..41).toList(), artifact.versions.drop(1).map { it.number })
        assertNull(artifact.changeFromPrevious(1))
        // v13 follows the kept original after trimming, so its change is measured against v1.
        assertEquals(DiffStats(1, 1), artifact.changeFromPrevious(13))
    }

    @Test fun largeHistoriesStayWithinTheSizeBudget() {
        var artifact = WorkbenchArtifact.create("fabc12345", "chat-1", "Big", ArtifactKind.MARKDOWN, "a".repeat(400_000), VersionOrigin.ANSWER, now = 0)
        for (index in 1..12) artifact = artifact.withVersion(('a' + index).toString().repeat(400_000), VersionOrigin.EDIT, now = index.toLong())
        assertTrue(artifact.versions.sumOf { it.source.length.toLong() * 3 } <= WorkbenchArtifact.MAX_HISTORY_BYTES)
        assertEquals(1, artifact.versions.first().number)
        assertEquals(13, artifact.latest.number)
        assertTrue(artifact.versions.size in 3 until 13)
    }

    @Test fun rejectsEmptyOversizedAndMalformedArtifacts() {
        assertTrue(runCatching { WorkbenchArtifact.create("fabc12345", "c", "T", ArtifactKind.HTML, " \n", VersionOrigin.ANSWER, 0) }.isFailure)
        assertTrue(runCatching { WorkbenchArtifact.create("fabc12345", "c", "T", ArtifactKind.HTML, "x".repeat(WorkbenchArtifact.MAX_SOURCE_BYTES + 1), VersionOrigin.ANSWER, 0) }.isFailure)
        assertTrue(runCatching { WorkbenchArtifact.create("../escape", "c", "T", ArtifactKind.HTML, page, VersionOrigin.ANSWER, 0) }.isFailure)
        val ok = WorkbenchArtifact.create("fabc12345", "c", "  ", ArtifactKind.SVG, "<svg/>", VersionOrigin.ANSWER, 0)
        assertEquals("SVG image", ok.title)
    }

    @Test fun idsAreStableAndDistinguishChatsMessagesAndFiles() {
        val one = WorkbenchIds.forFence("chat-1", "m1", "html", page)
        assertEquals(one, WorkbenchIds.forFence("chat-1", "m1", "html", page))
        assertNotEquals(one, WorkbenchIds.forFence("chat-2", "m1", "html", page))
        assertNotEquals(one, WorkbenchIds.forFence("chat-1", "m2", "html", page))
        val file = WorkbenchIds.forFile("chat-1", "account/chat-1/site.html")
        assertNotEquals(one, file)
        listOf(one, file).forEach { assertTrue(it, it.matches(Regex("[a-z0-9]{8,64}"))) }
    }

    @Test fun codeFencesFollowCommonMarkClosingRules() {
        val reply = "Here it is.\n\n````markdown\n# Notes\n```kotlin\nval x = 1\n```\n````\n\n~~~ html\n<p>tilde</p>\n~~~\n\n```svg\n<svg/>"
        val fences = CodeFences.parse(reply)
        assertEquals(2, fences.size)
        assertEquals("markdown", fences[0].language)
        assertEquals("# Notes\n```kotlin\nval x = 1\n```", fences[0].content)
        assertEquals("html", fences[1].language)
        assertEquals("<p>tilde</p>", fences[1].content)
    }

    @Test fun storeRoundTripsListsByChatAndDeletes() {
        val directory = Files.createTempDirectory("workbench").toFile()
        try {
            val store = WorkbenchStore(File(directory, "scope"))
            val first = WorkbenchArtifact.create(WorkbenchIds.forFence("chat-1", "m1", "html", page), "chat-1", "Launch page", ArtifactKind.HTML, page, VersionOrigin.ANSWER, 10)
                .withVersion(page.replace("Go", "Start"), VersionOrigin.EDIT, 20)
            val second = WorkbenchArtifact.create(WorkbenchIds.forFile("chat-2", "account/chat-2/a.md"), "chat-2", "Notes", ArtifactKind.MARKDOWN, "# Notes", VersionOrigin.AGENT_FILE, 30, filePath = "account/chat-2/a.md")
            store.save(first); store.save(second)
            assertEquals(first, store.load(first.id))
            assertEquals(listOf(second), store.forChat("chat-2"))
            assertNull(store.load("fdoesnotexist"))
            assertNull(store.load("../../etc"))
            store.deleteChat("chat-1")
            assertNull(store.load(first.id))
            assertEquals(second, store.load(second.id))
            File(File(directory, "scope"), "${second.id}.json").writeText("{not json")
            assertNull(store.load(second.id))
            assertTrue(store.forChat("chat-2").isEmpty())
            store.clear()
            assertFalse(File(directory, "scope").exists())
        } finally { directory.deleteRecursively() }
    }
}
