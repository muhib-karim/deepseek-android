package app.dsandroid.python

import android.app.Application
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.dsandroid.core.guard.WorkspacePath
import app.dsandroid.core.python.RunPythonTool
import app.dsandroid.core.skills.SkillFunctionTool
import app.dsandroid.core.tools.ToolContext
import app.dsandroid.core.tools.ToolResult
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * run_python on the real Chaquopy interpreter in the :python worker process. Chaquopy is built for
 * arm64-v8a and x86_64 only (app/build.gradle.kts); other emulator ABIs skip with an assumption message.
 */
@RunWith(AndroidJUnit4::class)
class PythonRuntimeTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val toolkit get() = AgentToolkit.get(app)
    private val workspace get() = WorkspacePath(File(app.filesDir, "workspace"))
    private val context get() = ToolContext(workspace, chatDir = "test/python")

    @Before fun requireSupportedAbi() {
        val abis = Build.SUPPORTED_ABIS.toList()
        assumeTrue("Chaquopy Python is packaged for arm64-v8a and x86_64 only; this device reports $abis, so run_python cannot start here.",
            abis.any { it == "arm64-v8a" || it == "x86_64" })
        File(workspace.root, "test/python").deleteRecursively()
    }

    @After fun cleanup() { File(workspace.root, "test/python").deleteRecursively() }

    private fun run(code: String, timeout: Int = 100): Pair<ToolResult, JsonObject> = runBlocking {
        val result = RunPythonTool(toolkit.python).run(buildJsonObject { put("code", code); put("timeout_s", timeout) }, context)
        result to Json.parseToJsonElement(result.content).jsonObject
    }

    @Test fun printsTwoPlusTwo() {
        val (result, json) = run("print(2 + 2)")
        assertFalse(result.content, result.isError)
        assertEquals("4\n", json["stdout"]!!.jsonPrimitive.content)
    }

    @Test fun keepsVariablesAndEchoesTheLastExpression() {
        run("total = sum(range(10))")
        val (_, json) = run("total * 2")
        assertEquals("90\n", json["stdout"]!!.jsonPrimitive.content)
    }

    @Test fun matplotlibFiguresBecomeArtifacts() {
        val (result, json) = run("import matplotlib.pyplot as plt\nplt.plot([1, 2, 3], [3, 1, 2])\nplt.title('Check')")
        assertFalse(result.content, result.isError)
        val image = result.artifacts.single()
        assertEquals("image/png", image.mimeType)
        assertTrue(File(workspace.root, image.path).length() > 1000)
        assertEquals(listOf("figure_1.png"), json["files"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test fun socketsAreRefused() {
        val (result, json) = run("import socket\nsocket.create_connection(('example.com', 443), timeout=2)")
        assertTrue(result.isError)
        assertTrue(json["error"]!!.jsonPrimitive.content.startsWith("PermissionError"))
    }

    @Test fun timeoutKillsTheWorkerAndTheNextRunStarts() {
        val (slow, json) = run("while True:\n    pass", timeout = 2)
        assertTrue(slow.isError)
        assertTrue(json["timed_out"]!!.jsonPrimitive.boolean)
        val (_, next) = run("print('alive')")
        assertEquals("alive\n", next["stdout"]!!.jsonPrimitive.content)
    }

    @Test fun builtInDataAnalysisSkillRunsItsMainPy() {
        File(workspace.root, "test/python").mkdirs()
        File(workspace.root, "test/python/sales.csv").writeText("region,revenue\nNorth,1200\nSouth,980\nNorth,300\n")
        val entry = toolkit.enabledSkills().first { it.manifest.name == "data-analysis" }
        val tool = SkillFunctionTool(entry, entry.manifest.tools.first { it.name == "describe_csv" }, toolkit.python)
        val result = runBlocking { tool.run(buildJsonObject { put("path", "sales.csv") }, context) }
        assertFalse(result.content, result.isError)
        val summary = Json.parseToJsonElement(Json.parseToJsonElement(result.content).jsonObject["stdout"]!!.jsonPrimitive.content).jsonObject
        assertEquals(3, summary["rows"]!!.jsonPrimitive.int)
        val revenue = summary["columns"]!!.jsonArray[1].jsonObject
        assertEquals(2480.0, revenue["sum"]!!.jsonPrimitive.double, 0.001)
    }
}
