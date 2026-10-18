package app.dsandroid.core.skills

import app.dsandroid.core.guard.WorkspacePath
import app.dsandroid.core.python.PythonExecutor
import app.dsandroid.core.python.PythonOutcome
import app.dsandroid.core.python.PythonRun
import app.dsandroid.core.tools.ToolContext
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SkillsTest {
    @get:Rule val temporary = TemporaryFolder()

    private val manifest = """
        {"name": "data-analysis", "description": "CSV statistics and charts", "version": "1.2.0",
         "tools": [{"name": "describe_csv", "description": "Summary statistics", "function": "describe",
                    "parameters": {"type": "object", "properties": {"path": {"type": "string"}}, "required": ["path"]}}],
         "pip": ["tabulate>=0.9"], "homepage": "ignored"}
    """.trimIndent()

    private fun zip(entries: Map<String, String>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip -> entries.forEach { (name, text) -> zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray()); zip.closeEntry() } }
        return output.toByteArray()
    }

    private fun pack(name: String = "data-analysis", version: String = "1.0.0", tools: Boolean = false): SkillPack {
        val toolList = if (tools) ",\"tools\":[{\"name\":\"run\",\"description\":\"Run\"}]" else ""
        val files = mutableMapOf(
            "manifest.json" to "{\"name\":\"$name\",\"description\":\"$name skill\",\"version\":\"$version\"$toolList}".toByteArray(),
            "SKILL.md" to "# $name\nUse it well.".toByteArray(),
        )
        if (tools) files["main.py"] = "def run():\n    return {'ok': True}\n".toByteArray()
        return SkillPackReader.fromFiles(files)
    }

    @Test fun manifestLoaderAcceptsTheDocumentedShape() {
        val parsed = SkillManifestLoader.parse(manifest)
        assertEquals("data-analysis", parsed.name)
        assertEquals("1.2.0", parsed.version)
        assertEquals("describe", parsed.tools.single().function)
        assertEquals(listOf("tabulate>=0.9"), parsed.pip)
        val minimal = SkillManifestLoader.parse("""{"name":"web-summary","description":"Summarise pages","version":"1"}""")
        assertTrue(minimal.tools.isEmpty() && minimal.pip.isEmpty())
    }

    @Test fun manifestLoaderRejectsInvalidManifests() {
        listOf(
            "not json",
            """{"description":"x","version":"1.0"}""",
            """{"name":"Bad Name","description":"x","version":"1.0"}""",
            """{"name":"ok","description":"","version":"1.0"}""",
            """{"name":"ok","description":"x","version":"latest"}""",
            """{"name":"ok","description":"x","version":"1.0","tools":[{"name":"Run Me","description":"x"}]}""",
            """{"name":"ok","description":"x","version":"1.0","tools":[{"name":"a","description":"x"},{"name":"a","description":"y"}]}""",
            """{"name":"ok","description":"x","version":"1.0","tools":[{"name":"a","description":"x","function":"os.system"}]}""",
            """{"name":"ok","description":"x","version":"1.0","tools":[{"name":"a","description":"x","parameters":{"type":"string"}}]}""",
            """{"name":"ok","description":"x","version":"1.0","pip":["pkg @ https://example.com/x.whl"]}""",
            """{"name":"ok","description":"x","version":"1.0","tools":"none"}""",
        ).forEach { text ->
            assertThrows("Accepted $text", IllegalArgumentException::class.java) { SkillManifestLoader.parse(text) }
        }
    }

    @Test fun zipPacksMayHaveOneTopLevelFolderAndMustBeComplete() {
        val nested = SkillPackReader.fromZip(zip(mapOf(
            "data-analysis/manifest.json" to manifest, "data-analysis/SKILL.md" to "# Data analysis",
            "data-analysis/main.py" to "def describe(path): return path", "__MACOSX/data-analysis/._main.py" to "junk")))
        assertEquals("data-analysis", nested.manifest.name)
        assertEquals(setOf("manifest.json", "SKILL.md", "main.py"), nested.files.keys)
        assertThrows(IllegalArgumentException::class.java) { SkillPackReader.fromZip(zip(mapOf("manifest.json" to manifest, "main.py" to ""))) }
        assertThrows(IllegalArgumentException::class.java) { SkillPackReader.fromZip(zip(mapOf("manifest.json" to manifest, "SKILL.md" to "x"))) }
        assertThrows(IllegalArgumentException::class.java) {
            SkillPackReader.fromZip(zip(mapOf("manifest.json" to manifest, "SKILL.md" to "x", "main.py" to "", "../escape.py" to "")))
        }
        assertThrows(IllegalArgumentException::class.java) { SkillPackReader.fromZip(ByteArray(SkillPackReader.MAX_ARCHIVE_BYTES + 1)) }
    }

    @Test fun folderPacksAreReadRecursively() {
        val folder = temporary.newFolder("pdf-report")
        folder.resolve("manifest.json").writeText("""{"name":"pdf-report","description":"Reports","version":"1.0.0"}""")
        folder.resolve("SKILL.md").writeText("# PDF report")
        folder.resolve("templates").mkdirs()
        folder.resolve("templates/outline.md").writeText("## Outline")
        folder.resolve(".git").mkdirs()
        folder.resolve(".git/config").writeText("ignored")
        val pack = SkillPackReader.fromDirectory(folder)
        assertEquals(setOf("manifest.json", "SKILL.md", "templates/outline.md"), pack.files.keys)
    }

    @Test fun storeInstallsEnablesDisablesAndRemoves() {
        val store = SkillStore(temporary.newFolder("skills"))
        store.syncBuiltIns(listOf(pack("pdf-report"), pack("web-summary")))
        assertEquals(listOf("pdf-report", "web-summary"), store.enabled().map { it.manifest.name })
        assertTrue(store.list().all { it.builtIn })
        val installed = store.install(pack("csv-tools", tools = true))
        assertTrue(installed.enabled && !installed.builtIn && installed.mainPy.isFile)
        store.setEnabled("web-summary", false)
        assertEquals(listOf("pdf-report", "csv-tools"), store.enabled().map { it.manifest.name })
        assertThrows(IllegalArgumentException::class.java) { store.install(pack("pdf-report")) }
        assertThrows(IllegalArgumentException::class.java) { store.remove("pdf-report") }
        assertThrows(IllegalArgumentException::class.java) { store.setEnabled("nope", true) }
        store.install(pack("csv-tools", version = "2.0.0", tools = true))
        assertEquals("2.0.0", store.list().first { it.manifest.name == "csv-tools" }.manifest.version)
        assertTrue(store.remove("csv-tools"))
        assertFalse(store.remove("csv-tools"))
        assertEquals(listOf("pdf-report"), store.enabled().map { it.manifest.name })
        // Built-ins are refreshed when their version changes and dropped when no longer shipped.
        store.syncBuiltIns(listOf(pack("pdf-report", version = "1.1.0")))
        assertEquals(listOf("pdf-report"), store.list().map { it.manifest.name })
        assertEquals("1.1.0", store.list().single().manifest.version)
        assertEquals("# pdf-report\nUse it well.", store.instructions(store.list().single()))
    }

    @Test fun skillToolLoadsInstructionsForEnabledSkillsOnly() = runBlocking<Unit> {
        val store = SkillStore(temporary.newFolder("skills"))
        store.syncBuiltIns(listOf(pack("pdf-report"), pack("data-analysis", tools = true)))
        store.setEnabled("pdf-report", false)
        val tool = SkillTool({ store.enabled() }, store::instructions)
        val ctx = ToolContext(WorkspacePath(temporary.newFolder("workspace")))
        val loaded = tool.run(buildJsonObject { put("name", "data-analysis") }, ctx)
        assertFalse(loaded.isError)
        assertTrue(loaded.content.startsWith("Skill data-analysis 1.0.0\nTools from this skill: data_analysis__run\n"))
        assertTrue(loaded.content.endsWith("Use it well."))
        val disabled = tool.run(buildJsonObject { put("name", "pdf-report") }, ctx)
        assertTrue(disabled.isError)
        assertTrue(disabled.content.contains("Available: data-analysis"))
        assertEquals("\n\nSkills (call the skill tool with the exact name to load full instructions before using one):\n- data-analysis: data-analysis skill",
            SkillPrompt.section(store.enabled()))
    }

    @Test fun skillFunctionsRunMainPyThroughThePythonHarness() = runBlocking<Unit> {
        val store = SkillStore(temporary.newFolder("skills"))
        val entry = store.install(SkillPackReader.fromFiles(mapOf(
            "manifest.json" to manifest.toByteArray(), "SKILL.md" to "x".toByteArray(), "main.py" to "def describe(path): return path".toByteArray())))
        var request: PythonRun? = null
        val executor = PythonExecutor { run -> request = run; PythonOutcome(stdout = "{\"rows\": 3}\n") }
        val tool = SkillFunctionTool(entry, entry.manifest.tools.single(), executor)
        assertEquals("data_analysis__describe_csv", tool.name)
        assertEquals("[data-analysis skill] Summary statistics", tool.description)
        assertEquals("object", tool.schema["type"]!!.jsonPrimitive.content)
        val ctx = ToolContext(WorkspacePath(temporary.newFolder("workspace")), chatDir = "chats/1")
        val result = tool.run(buildJsonObject { put("path", "data \"q\".csv") }, ctx)
        assertFalse(result.isError)
        val code = request!!.code
        assertTrue(code.contains("spec_from_file_location(\"skill_data_analysis\", " + JsonPrimitive(entry.mainPy.absolutePath)))
        assertTrue(code.contains("_skill_module.describe(**_skill_json.loads("))
        assertTrue(code.contains(JsonPrimitive("""{"path":"data \"q\".csv"}""").toString()))
        assertEquals(90, request!!.timeoutSeconds)
        assertThrows(IllegalArgumentException::class.java) { SkillFunctionTool.harness(entry.mainPy, "skill_x", "os.system", JsonObject(emptyMap())) }
    }
}
