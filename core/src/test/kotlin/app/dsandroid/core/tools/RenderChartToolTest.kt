package app.dsandroid.core.tools

import app.dsandroid.core.guard.Decision
import app.dsandroid.core.guard.GuardPolicy
import app.dsandroid.core.guard.GuardSettings
import app.dsandroid.core.guard.WorkspacePath
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RenderChartToolTest {
    @get:Rule val temporary = TemporaryFolder()
    private val request = """{"data":{"labels":["A","B"],"series":[{"name":"Count","values":[-2,3]}]},"spec":{"type":"bar","title":"Counts"}}"""

    @Test fun dataAndSpecCreateAnInlineSvgArtifactWithoutOutsideAccess() = runTest {
        val workspace = WorkspacePath(temporary.newFolder())
        val tool = RenderChartTool()
        val args = Json.parseToJsonElement(request).jsonObject
        assertEquals(Decision.ALLOW, GuardPolicy(ToolRegistry(listOf(tool)), workspace)
            .decide(tool.name, args, GuardSettings()))
        val result = tool.run(args, ToolContext(workspace))
        assertFalse(result.isError)
        val artifact = result.artifacts.single()
        assertEquals("image/svg+xml", artifact.mimeType)
        val svg = workspace.resolve(artifact.path).readText()
        assertTrue(svg.contains("Counts"))
        assertTrue(svg.contains("<rect"))
        assertFalse(svg.contains("NaN"))
        assertFalse(svg.contains("Infinity"))
    }

    @Test fun invalidSeriesNeverLeaveAFile() = runTest {
        val workspace = WorkspacePath(temporary.newFolder())
        for (bad in listOf(request.replace("[-2,3]", "[3]"), request.replace("[-2,3]", "[\"2\",3]"),
            request.replace("[-2,3]", "[1e99,3]"), request.replace("\"bar\"", "\"pie\""))) {
            try {
                RenderChartTool().run(Json.parseToJsonElement(bad).jsonObject, ToolContext(workspace))
                fail("Invalid chart accepted")
            } catch (_: IllegalArgumentException) { }
        }
        assertEquals(0, workspace.root.listFiles()!!.size)
    }

    @Test fun labelsCannotInjectMarkupAndExistingFilesCannotBeReplaced() = runTest {
        val workspace = WorkspacePath(temporary.newFolder())
        val args = Json.parseToJsonElement(request.replace("\"Counts\"", "\"<script>alert(1)</script>\"")
            .dropLast(1) + ",\"fileName\":\"chart.svg\"}").jsonObject
        RenderChartTool().run(args, ToolContext(workspace))
        val svg = workspace.resolve("chart.svg").readText()
        assertFalse(svg.contains("<script>"))
        try { RenderChartTool().run(args, ToolContext(workspace)); fail("Overwrote chart") }
        catch (_: java.nio.file.FileAlreadyExistsException) { }
        assertEquals(svg, workspace.resolve("chart.svg").readText())
    }
}
