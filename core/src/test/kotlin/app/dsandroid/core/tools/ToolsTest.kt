package app.dsandroid.core.tools

import app.dsandroid.core.guard.WorkspacePath
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ToolsTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun createsArtifactsReadsAndListsOnlyWorkspace() = runBlocking<Unit> {
        val root = temporary.newFolder("workspace")
        val context = ToolContext(WorkspacePath(root))
        val markdown = CreateMarkdownTool().run(buildJsonObject { put("fileName", "note.md"); put("content", "# Hello") }, context)
        assertEquals("text/markdown", markdown.artifacts.single().mimeType)
        assertEquals("note.md", markdown.artifacts.single().path)
        assertEquals("# Hello", File(root, "note.md").readText())
        val csv = CreateCsvTool().run(buildJsonObject { put("fileName", "table.csv"); put("rows", JsonArray(listOf(JsonArray(listOf(JsonPrimitive("a,b")))))) }, context)
        assertEquals("text/csv", csv.artifacts.single().mimeType)
        assertEquals("\"a,b\"\r\n", File(root, "table.csv").readText())
        val svg = CreateSvgTool().run(buildJsonObject { put("fileName", "drawing.svg"); put("content", "<svg><rect width=\"10\"/></svg>") }, context)
        assertEquals("image/svg+xml", svg.artifacts.single().mimeType)
        val read = ReadFileTool().run(buildJsonObject { put("path", "note.md") }, context)
        assertEquals("# Hello", read.content)
        val listing = ListFilesTool().run(JsonObject(emptyMap()), context)
        assertTrue(listing.content.contains("note.md"))
        try { ReadFileTool().run(buildJsonObject { put("path", "../secret") }, context); fail("Traversal accepted") }
        catch (_: IllegalArgumentException) { }
        try { CreateMarkdownTool().run(buildJsonObject { put("fileName", "note.md"); put("content", "overwrite") }, context); fail("Overwrite accepted") }
        catch (_: java.nio.file.FileAlreadyExistsException) { }
        assertEquals("# Hello", File(root, "note.md").readText())
    }
    @Test fun registryRejectsDuplicatesAndProducesModelSpecs() {
        val tool = CreateMarkdownTool()
        assertThrows(IllegalArgumentException::class.java) { ToolRegistry(listOf(tool, tool)) }
        val registry = ToolRegistry(listOf(tool))
        assertNull(registry["missing"])
        assertEquals("create_markdown", registry.specs().single().function.name)
        assertEquals(tool.schema, registry.specs().single().function.parameters)
    }
}
