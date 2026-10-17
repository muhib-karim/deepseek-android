package app.dsandroid.core.tools

import app.dsandroid.core.artifacts.*

import app.dsandroid.core.guard.WorkspacePath
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ArtifactStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun store(root: File, limit: Int = 20) = ArtifactStore(WorkspacePath(root), limit)

    @Test fun editsSurviveReopeningAndNeverOverwriteOriginal() {
        val root = temporary.newFolder()
        val original = store(root).create("html", "demo", "<h1>one</h1>")
        val edited = store(root).edit(original, "<h1>two</h1>")
        val reopened = store(root)
        assertNotEquals(original.path, edited.path)
        assertEquals("<h1>one</h1>", reopened.read(original))
        assertEquals("<h1>two</h1>", reopened.read(edited))
        assertEquals(listOf(edited, original), reopened.history(edited))
        assertThrows(java.nio.file.FileAlreadyExistsException::class.java) { reopened.create("html", "demo", "replacement") }
    }

    @Test fun boundedHistoryKeepsOriginalAndNewestVersionsAndIgnoresPartialJournal() {
        val root = temporary.newFolder()
        val first = store(root, 3).create("md", "note", "zero")
        var current = first
        repeat(6) { current = store(root, 3).edit(current, "edit $it") }
        val history = store(root, 3).history(first)
        assertEquals(3, history.size)
        assertEquals(first, history.last())
        assertEquals(current, history.first())
        File(root, ".artifact-history/orphan.tmp").writeText("incomplete")
        assertEquals(history, store(root, 3).history(first))
        assertEquals("zero", store(root, 3).read(first))
    }

    @Test fun supportsFormatsSanitizesSvgAndRejectsUnsafeOrOversizedInput() {
        val root = temporary.newFolder()
        val store = store(root)
        val svg = store.create("svg", "drawing", "<svg><script>alert(1)</script><rect width=\"10\"/></svg>")
        assertFalse(store.read(svg).contains("script"))
        assertEquals("application/json", store.create("json", "data", "{\"x\":1}").mimeType)
        assertEquals("text/vnd.mermaid", store.create("mermaid", "diagram", "flowchart TD\nA --> B").mimeType)
        assertThrows(IllegalArgumentException::class.java) { store.create("html", "../outside", "x") }
        assertThrows(IllegalArgumentException::class.java) { store.create("zip", "archive", "x") }
        assertThrows(IllegalArgumentException::class.java) { store.create("txt", "large", "x".repeat(2 * 1024 * 1024 + 1)) }
        assertThrows(IllegalArgumentException::class.java) { store.create("json", "invalid", "{") }
    }

    @Test fun fenceImportIsIdempotentAndKeepsVersionHistory() {
        val root = temporary.newFolder()
        val first = store(root).importFence("turn-1", 0, "html", "hello")
        val edited = store(root).edit(first, "edited")
        val again = store(root).importFence("turn-1", 0, "html", "hello")
        assertEquals(first, again)
        assertEquals(edited, store(root).history(again).first())
        assertNotEquals(first, store(root).importFence("turn-2", 0, "html", "hello"))
    }

    @Test fun existingNestedWorkspaceArtifactsCanBeEditedAndReopened() {
        val root = temporary.newFolder()
        val directory = File(root, "reports").apply { mkdirs() }
        File(directory, "report.html").writeText("original")
        val original = Artifact("report.html", "text/html", "reports/report.html")
        val edited = store(root).edit(original, "updated")
        assertEquals(listOf(edited, original), store(root).history(original))
        assertEquals("original", store(root).read(original))
        assertEquals("updated", store(root).read(edited))
    }

    @Test fun unsafeEditsDoNotPublishAVersionAndSymlinkReadsAreRejected() {
        val root = temporary.newFolder()
        val first = store(root).create("json", "data", "{\"v\":1}")
        assertThrows(IllegalArgumentException::class.java) { store(root).edit(first, "{") }
        assertEquals(listOf(first), store(root).history(first))
        val outside = temporary.newFile().apply { writeText("outside") }
        java.nio.file.Files.delete(File(root, first.path).toPath())
        java.nio.file.Files.createSymbolicLink(File(root, first.path).toPath(), outside.toPath())
        assertThrows(IllegalArgumentException::class.java) { store(root).read(first) }
        assertEquals("outside", outside.readText())
    }

    @Test fun downloadableTextFormatsKeepTheirExtensionMimeAndExactUtf8Content() {
        val root = temporary.newFolder()
        val cases = listOf(
            Triple("md", "text/markdown", "# Notes\n"),
            Triple("html", "text/html", "<h1>Notes</h1>"),
            Triple("csv", "text/csv", "name,value\r\na,2\r\n"),
            Triple("txt", "text/plain", "Unicode: \u2603\n"),
            Triple("json", "application/json", "{\"value\":2}")
        )
        cases.forEach { (extension, mime, content) ->
            val artifact = store(root).create(extension, "export", content)
            assertEquals("export.$extension", artifact.fileName)
            assertEquals(mime, artifact.mimeType)
            assertEquals(content, store(root).read(artifact))
        }
    }

    @Test fun createToolProducesOrdinaryCompatibleArtifact() = runBlocking {
        val root = temporary.newFolder()
        val result = CreateArtifactTool().run(buildJsonObject {
            put("title", "page"); put("format", "html"); put("content", "<button>Click</button>")
        }, ToolContext(WorkspacePath(root)))
        assertFalse(result.isError)
        assertEquals(Artifact("page.html", "text/html", "page.html"), result.artifacts.single())
    }
}
