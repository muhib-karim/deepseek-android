package app.dsandroid.core.tools

import app.dsandroid.core.guard.Decision
import app.dsandroid.core.guard.GuardPolicy
import app.dsandroid.core.guard.GuardSettings
import app.dsandroid.core.guard.WorkspacePath
import java.util.zip.ZipInputStream
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AgentFileToolsTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun context(): ToolContext = ToolContext(WorkspacePath(temporary.newFolder("workspace")), chatDir = "account/chat-1")
    private fun args(vararg pairs: Pair<String, Any>) = buildJsonObject {
        pairs.forEach { (key, value) ->
            when (value) {
                is String -> put(key, value)
                is Int -> put(key, value)
                is JsonElement -> put(key, value)
                else -> error("Unsupported test argument")
            }
        }
    }

    @Test fun writeThenReadRoundTripsWithLineNumbersOffsetAndLimit() = runBlocking {
        val ctx = context()
        val created = WriteTool().run(args("file_path" to "notes/plan.md", "content" to "one\ntwo\r\nthree\nfour"), ctx)
        assertEquals("Created notes/plan.md (19 bytes)", created.content)
        assertTrue(created.artifacts.isEmpty())
        assertTrue(ctx.workspace.root.resolve("account/chat-1/notes/plan.md").isFile)
        assertEquals("     1\tone\n     2\ttwo\n     3\tthree\n     4\tfour", ReadTool().run(args("file_path" to "notes/plan.md"), ctx).content)
        assertEquals("     2\ttwo\n     3\tthree\n[1 more lines; continue with offset 4]",
            ReadTool().run(args("file_path" to "notes/plan.md", "offset" to 2, "limit" to 2), ctx).content)
        val replaced = WriteTool().run(args("file_path" to "notes/plan.md", "content" to "new"), ctx)
        assertTrue(replaced.content.startsWith("Replaced"))
        assertEquals("     1\tnew", ReadTool().run(args("file_path" to "./notes/plan.md"), ctx).content)
        val lenient = buildJsonObject { put("file_path", "notes/plan.md"); put("offset", 1.0); put("limit", JsonNull) }
        assertEquals("     1\tnew", ReadTool().run(lenient, ctx).content)
    }

    @Test fun pathsCannotLeaveTheChatFolder() = runBlocking {
        val ctx = context()
        listOf("../other/x.txt", "/etc/passwd", "a/../../x", "", "C:\\x", "~/x").forEach { path ->
            try {
                WriteTool().run(args("file_path" to path, "content" to "x"), ctx)
                fail("Accepted $path")
            } catch (_: IllegalArgumentException) { }
        }
        ctx.workspace.root.resolve("account/chat-2").mkdirs()
        ctx.workspace.root.resolve("account/chat-2/secret.txt").writeText("other chat")
        assertThrows(IllegalArgumentException::class.java) { runBlocking { ReadTool().run(args("file_path" to "../chat-2/secret.txt"), ctx) } }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { ReadTool().run(args("file_path" to "missing.txt"), ctx) } }
    }

    @Test fun presentReturnsWorkspaceRelativeArtifactCards() = runBlocking {
        val ctx = context()
        WriteTool().run(args("file_path" to "out/chart.svg", "content" to "<svg/>"), ctx)
        WriteTool().run(args("file_path" to "report.md", "content" to "# Hi"), ctx)
        val files = buildJsonArray {
            add(buildJsonObject { put("path", "out/chart.svg"); put("description", "Chart") })
            add(buildJsonObject { put("path", "report.md") })
        }
        val result = PresentTool().run(args("files" to files), ctx)
        assertEquals(listOf("account/chat-1/out/chart.svg", "account/chat-1/report.md"), result.artifacts.map { it.path })
        assertEquals(listOf("image/svg+xml", "text/markdown"), result.artifacts.map { it.mimeType })
        assertEquals("chart.svg", result.artifacts.first().fileName)
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { PresentTool().run(args("files" to buildJsonArray { add(buildJsonObject { put("path", "nope.pdf") }) }), ctx) }
        }
        val five = buildJsonArray { repeat(5) { add(buildJsonObject { put("path", "report.md") }) } }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { PresentTool().run(args("files" to five), ctx) } }
    }

    @Test fun writeFileCreatesTypedDocumentsWithoutOverwriting() = runBlocking {
        val ctx = context()
        val docx = WriteFileTool().run(args("file_path" to "summary.docx", "content" to "# Summary\n\n- point", "title" to "Summary"), ctx)
        val card = docx.artifacts.single()
        assertEquals("application/vnd.openxmlformats-officedocument.wordprocessingml.document", card.mimeType)
        val names = mutableListOf<String>()
        ZipInputStream(ctx.workspace.resolve(card.path).inputStream()).use { zip -> while (true) names += (zip.nextEntry ?: break).name }
        assertTrue("word/document.xml" in names)

        val csv = WriteFileTool().run(args("file_path" to "data.csv", "content" to "a,b\n1,2"), ctx).artifacts.single()
        assertEquals("a,b\r\n1,2\r\n", ctx.workspace.resolve(csv.path).readText())
        val again = WriteFileTool().run(args("file_path" to "data.csv", "content" to "c\n3"), ctx).artifacts.single()
        assertEquals("data-2.csv", again.fileName)
        assertEquals("a,b\r\n1,2\r\n", ctx.workspace.resolve(csv.path).readText())

        assertEquals("text/html", WriteFileTool().run(args("file_path" to "page.html", "content" to "<p>Hi</p>"), ctx).artifacts.single().mimeType)
        assertThrows(IllegalArgumentException::class.java) { runBlocking { WriteFileTool().run(args("file_path" to "x.json", "content" to "{bad"), ctx) } }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { WriteFileTool().run(args("file_path" to "x.exe", "content" to "MZ"), ctx) } }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { WriteFileTool().run(args("file_path" to "x.pdf", "content" to "x"), ctx) } }
    }

    @Test fun guardAllowsChatFolderPathsAndDeniesEscapes() {
        val ctx = context()
        val pdf = object : Tool {
            override val name = "create_pdf"
            override val description = "PDF"
            override val schema = objectSchema(emptyMap())
            override val risk = Risk.SAFE
            override val kind = ToolKind.WRITE
            override suspend fun run(args: JsonObject, ctx: ToolContext) = ToolResult("ok")
        }
        val registry = ToolRegistry(listOf(WriteTool(), ReadTool(), PresentTool(), WriteFileTool(), pdf))
        val guard = GuardPolicy(registry, ctx.workspace)
        val settings = GuardSettings()
        assertEquals(Decision.ALLOW, guard.decide("write", args("file_path" to "a.md", "content" to "x"), settings))
        assertEquals(Decision.ALLOW, guard.decide("read", args("file_path" to "a.md"), settings))
        assertEquals(Decision.DENY, guard.decide("write", args("file_path" to "../a.md", "content" to "x"), settings))
        assertEquals(Decision.DENY, guard.decide("read", args("file_path" to "/etc/hosts"), settings))
        assertEquals(Decision.ALLOW, guard.decide("create_pdf", args("title" to "T", "markdown" to "x"), settings))
        assertEquals(Decision.ALLOW, guard.decide("present", args("files" to buildJsonArray { add(buildJsonObject { put("path", "a.md") }) }), settings))
    }
}
