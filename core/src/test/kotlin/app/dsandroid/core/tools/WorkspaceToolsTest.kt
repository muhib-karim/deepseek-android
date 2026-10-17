package app.dsandroid.core.tools

import app.dsandroid.core.guard.WorkspacePath
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WorkspaceToolsTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun createsReadsCapsAndRecursivelyZips() = runBlocking {
        val ctx = ToolContext(WorkspacePath(temporary.newFolder()))
        CreateFileTool().run(buildJsonObject { put("path", "folder/note.txt"); put("content", "abcdef") }, ctx)
        assertTrue(ReadFileTool().run(buildJsonObject { put("path", "folder/note.txt"); put("max_chars", 3) }, ctx).content.startsWith("abc"))
        ZipFilesTool().run(buildJsonObject { put("sources", JsonArray(listOf(JsonPrimitive("folder")))); put("zip_path", "bundle.zip") }, ctx)
        assertTrue(ListZipTool().run(buildJsonObject { put("zip_path", "bundle.zip") }, ctx).content.contains("folder/note.txt"))
        try { CreateFileTool().run(buildJsonObject { put("path", "../escape.txt"); put("content", "x") }, ctx); fail() } catch (_: IllegalArgumentException) { }
    }
    @Test fun zipRejectsSymbolicLinks() = runBlocking {
        val root = temporary.newFolder(); val ctx = ToolContext(WorkspacePath(root))
        java.nio.file.Files.createSymbolicLink(java.io.File(root,"link").toPath(), temporary.newFile().toPath())
        try { ZipFilesTool().run(buildJsonObject { put("sources", JsonArray(listOf(JsonPrimitive("link")))); put("zip_path", "x.zip") }, ctx); fail() } catch (_: IllegalArgumentException) { }
    }
}
