package app.dsandroid.python

import android.content.Context
import android.os.Build
import app.dsandroid.core.python.PipInstallTool
import app.dsandroid.core.python.RunPythonTool
import app.dsandroid.core.python.WheelInstaller
import app.dsandroid.core.sandbox.CodeExecutor
import app.dsandroid.core.skills.SkillEntry
import app.dsandroid.core.skills.SkillFunctionTool
import app.dsandroid.core.skills.SkillPack
import app.dsandroid.core.skills.SkillPackReader
import app.dsandroid.core.skills.SkillStore
import app.dsandroid.core.skills.SkillTool
import app.dsandroid.core.tools.*
import app.dsandroid.tools.CreateImageTool
import app.dsandroid.tools.CreatePdfTool
import java.io.File

/**
 * Process-wide agent capabilities shared by account mode and the API-key client: the on-device Python
 * worker, the runtime package installer and the skill store. One instance per app process.
 */
class AgentToolkit private constructor(context: Context) {
    companion object {
        /** Packages built into the APK by Chaquopy (app/build.gradle.kts); never downloaded at runtime. */
        val BUNDLED = linkedMapOf("numpy" to "1.26.2", "matplotlib" to "3.8.4", "pillow" to "built in", "contourpy" to "built in",
            "cycler" to "built in", "fonttools" to "built in", "kiwisolver" to "built in", "packaging" to "built in",
            "pyparsing" to "built in", "python-dateutil" to "built in", "six" to "built in")

        @Volatile private var instance: AgentToolkit? = null
        fun get(context: Context): AgentToolkit = instance ?: synchronized(this) {
            instance ?: AgentToolkit(context.applicationContext).also { instance = it }
        }
    }

    private val app = context.applicationContext
    val siteDir = File(app.filesDir, "python/site-packages")
    val python = PythonWorker(app, siteDir)
    val installer = WheelInstaller(siteDir, BUNDLED, machine = if (Build.SUPPORTED_ABIS.firstOrNull() == "x86_64") "x86_64" else "aarch64")
    val skills = SkillStore(File(app.filesDir, "skills"))
    @Volatile private var builtInsSynced = false

    /** Copies the built-in skills from assets once per process. Call off the main thread. */
    fun ensureBuiltIns() {
        if (builtInsSynced) return
        synchronized(this) {
            if (builtInsSynced) return
            skills.syncBuiltIns(builtInPacks())
            builtInsSynced = true
        }
    }

    private fun builtInPacks(): List<SkillPack> = app.assets.list("skills").orEmpty().sorted().mapNotNull { folder ->
        runCatching {
            val files = linkedMapOf<String, ByteArray>()
            fun collect(path: String, relative: String) {
                val children = app.assets.list(path).orEmpty()
                if (children.isEmpty()) files[relative] = app.assets.open(path).use { it.readBytes() }
                else children.forEach { child -> collect("$path/$child", if (relative.isEmpty()) child else "$relative/$child") }
            }
            collect("skills/$folder", "")
            SkillPackReader.fromFiles(files)
        }.getOrNull()
    }

    fun enabledSkills(): List<SkillEntry> {
        ensureBuiltIns()
        return skills.enabled()
    }

    /**
     * The account route's tools (app.dsandroid.tools.ToolCatalog, route ACCOUNT): dsh-named file tools, documents, Excel,
     * PowerPoint, charts, PNG images, ZIP, the code sandbox, skills and web_fetch and web search, plus native Python and skill
     * functions as TrustedTool (the caller offers those only with trusted execution on). [search] is null when the configured
     * search service is not usable. The API-key client builds its list from the same tool classes.
     */
    fun accountTools(search: WebSearchTool?, sandbox: CodeExecutor): List<Tool> {
        val base = listOf(
            TrustedTool(RunPythonTool(python)), TrustedTool(PipInstallTool(installer)), WriteTool(), ReadTool(), PresentTool(),
            CreatePdfTool(), WriteFileTool(), SkillTool({ enabledSkills() }, skills::instructions), WebFetchTool(),
            CreateXlsxTool(), CreatePptxTool(), CreateChartTool(), CreateImageTool(), ZipFilesTool(), CodeInterpreterTool(sandbox),
        ) + listOfNotNull(search)
        return base + skillFunctions(base.map { it.name }.toSet())
    }

    /** Enabled skills' functions as TrustedTool (Python with the app's rights), skipping names already in [taken]. */
    fun skillFunctions(taken: Set<String>): List<Tool> = enabledSkills()
        .flatMap { entry -> entry.manifest.tools.map { TrustedTool(SkillFunctionTool(entry, it, python)) } }
        .filter { it.name !in taken }.distinctBy { it.name }
}
