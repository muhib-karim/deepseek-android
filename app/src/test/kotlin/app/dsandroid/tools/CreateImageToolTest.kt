package app.dsandroid.tools

import app.dsandroid.core.guard.WorkspacePath
import app.dsandroid.core.tools.ToolContext
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CreateImageToolTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun rendererReceivesOnlySanitizedSvgAndOutputIsShareablePng() = runBlocking<Unit> {
        var rendered = ""
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47)
        val tool = CreateImageTool(SvgRasterizer { svg -> rendered = svg; png })
        val workspace = WorkspacePath(temporary.newFolder())
        val result = tool.run(buildJsonObject {
            put("fileName", "chart.png")
            put("svg", """<svg xmlns="http://www.w3.org/2000/svg" width="100" height="100"><script>bad()</script><image href="https://example.com/remote"/><rect width="20" height="20"/></svg>""")
        }, ToolContext(workspace))
        assertFalse(rendered.contains("script"))
        assertFalse(rendered.contains("https://example.com"))
        assertTrue(rendered.contains("rect"))
        assertArrayEquals(png, workspace.resolve("chart.png").readBytes())
        assertEquals("image/png", result.artifacts.single().mimeType)
    }

    @Test fun unsafeOutputCannotEscapeWorkspace() = runBlocking<Unit> {
        val workspace = WorkspacePath(temporary.newFolder())
        try {
            CreateImageTool(SvgRasterizer { byteArrayOf(1) }).run(buildJsonObject {
                put("fileName", "../escape.png"); put("svg", "<svg/>")
            }, ToolContext(workspace))
            fail("Traversal accepted")
        } catch (_: IllegalArgumentException) { }
        assertFalse(java.io.File(workspace.root.parentFile, "escape.png").exists())
    }
}
