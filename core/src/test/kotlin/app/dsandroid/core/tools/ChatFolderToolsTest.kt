package app.dsandroid.core.tools

import app.dsandroid.core.guard.WorkspacePath
import java.util.zip.ZipInputStream
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** With a chat folder (account mode, API chats), every workspace tool makes and sees only that chat's files. */
class ChatFolderToolsTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun failure(block: suspend () -> Unit): Throwable? = runCatching { runBlocking { block() } }.exceptionOrNull()

    @Test fun generatorsWriteIntoTheChatFolderAndToolsStayInIt() = runBlocking<Unit> {
        val workspace = WorkspacePath(temporary.newFolder())
        val chat = ToolContext(workspace, chatDir = "accounts/a1/chat-1")
        val other = ToolContext(workspace, chatDir = "accounts/a2/chat-9")
        workspace.resolve("accounts/a2/chat-9").mkdirs()
        workspace.resolve("accounts/a2/chat-9/secret.txt").writeText("another account's file")

        val csv = CreateCsvTool().run(buildJsonObject { put("fileName", "data.csv"); putJsonArray("rows") { addJsonArray { add("a"); add("b") } } }, chat)
        assertEquals("accounts/a1/chat-1/data.csv", csv.artifacts.single().path)
        assertTrue(workspace.resolve("accounts/a1/chat-1/data.csv").isFile)
        assertFalse(workspace.resolve("data.csv").exists())
        val chart = CreateChartTool().run(buildJsonObject {
            put("fileName", "chart.svg"); put("type", "bar"); put("title", "T"); putJsonArray("labels") { add("x") }
            putJsonArray("series") { addJsonObject { put("name", "s"); putJsonArray("values") { add(1) } } }
        }, chat)
        assertEquals("accounts/a1/chat-1/chart.svg", chart.artifacts.single().path)
        // The same name in another chat is a different file.
        assertEquals("accounts/a2/chat-9/data.csv", CreateCsvTool().run(buildJsonObject { put("fileName", "data.csv"); putJsonArray("rows") { addJsonArray { add("z") } } }, other).artifacts.single().path)

        assertEquals("chart.svg\ndata.csv", ListFilesTool().run(JsonObject(emptyMap()), chat).content)
        assertNotNull(failure { ReadFileTool().run(buildJsonObject { put("path", "../../a2/chat-9/secret.txt") }, chat) })
        assertNotNull(failure { ZipFilesTool().run(buildJsonObject { putJsonArray("sources") { add("../../a2/chat-9/secret.txt") }; put("zip_path", "leak.zip") }, chat) })
        assertNotNull(failure { ZipFilesTool().run(buildJsonObject { putJsonArray("sources") { add("/secret.txt") }; put("zip_path", "leak.zip") }, chat) })

        val zip = ZipFilesTool().run(buildJsonObject { putJsonArray("sources") { add(".") }; put("zip_path", "all.zip") }, chat)
        assertEquals("accounts/a1/chat-1/all.zip", zip.artifacts.single().path)
        val names = ZipInputStream(workspace.resolve("accounts/a1/chat-1/all.zip").inputStream()).use { input ->
            generateSequence { input.nextEntry?.name }.toList()
        }
        // Entries are named inside the chat, never with the account or chat folders.
        assertEquals(listOf("chart.svg", "data.csv"), names)
        assertEquals("chart.svg\ndata.csv", ListZipTool().run(buildJsonObject { put("zip_path", "all.zip") }, chat).content)
    }
}
