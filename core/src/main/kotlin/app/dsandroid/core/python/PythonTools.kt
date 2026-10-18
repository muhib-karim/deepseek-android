package app.dsandroid.core.python

import app.dsandroid.core.tools.*
import java.io.File
import kotlinx.serialization.json.*

/** One execution in the on-device interpreter; [namespace] keeps variables per chat. */
data class PythonRun(val code: String, val workingDir: File, val timeoutSeconds: Int, val namespace: String)

data class PythonOutcome(
    val stdout: String = "",
    val stderr: String = "",
    val error: String? = null,
    val timedOut: Boolean = false,
    val truncated: Boolean = false,
)

/** Platform adapter (Chaquopy on Android). It owns the wall-clock limit and must stop work on timeout. */
fun interface PythonExecutor {
    suspend fun run(request: PythonRun): PythonOutcome
}

object PythonTools {
    const val MAX_CODE_CHARS = 100_000
    const val MAX_OUTPUT_CHARS = 16_000
    const val MAX_TIMEOUT_SECONDS = 110
    private const val MAX_ARTIFACTS = 12
    private const val MAX_SCANNED = 2000

    /** Runs code in the chat folder and turns new or changed files into artifact cards. */
    suspend fun execute(executor: PythonExecutor, code: String, timeoutSeconds: Int, ctx: ToolContext): ToolResult {
        require(code.isNotBlank() && code.length <= MAX_CODE_CHARS) { "Code must contain 1 to $MAX_CODE_CHARS characters" }
        require(timeoutSeconds in 1..MAX_TIMEOUT_SECONDS) { "timeout_s must be between 1 and $MAX_TIMEOUT_SECONDS" }
        val directory = ctx.chatRoot()
        val before = snapshot(directory)
        val outcome = executor.run(PythonRun(code, directory, timeoutSeconds, ctx.chatDir.ifEmpty { "default" }))
        val changed = changedFiles(directory, before)
        var budget = MAX_OUTPUT_CHARS
        fun take(text: String): String = text.take(budget).also { budget -= it.length }
        val stdout = take(outcome.stdout)
        val stderr = take(outcome.stderr)
        val truncated = outcome.truncated || stdout.length < outcome.stdout.length || stderr.length < outcome.stderr.length
        val result = buildJsonObject {
            put("stdout", stdout)
            put("stderr", stderr)
            outcome.error?.let { put("error", it.take(2000)) }
            put("timed_out", outcome.timedOut)
            if (truncated) put("truncated", true)
            putJsonArray("files") { changed.forEach { add(ctx.chatPath(it)) } }
        }
        return ToolResult(result.toString(), isError = outcome.error != null || outcome.timedOut,
            artifacts = changed.take(MAX_ARTIFACTS).map { ctx.artifactFor(it) })
    }

    /** Relative path to (modified time, size); hidden entries and caches are ignored. */
    fun snapshot(directory: File): Map<String, Pair<Long, Long>> {
        val files = linkedMapOf<String, Pair<Long, Long>>()
        fun visit(dir: File, depth: Int) {
            if (depth > 4) return
            dir.listFiles().orEmpty().sortedBy { it.name }.forEach { file ->
                if (files.size >= MAX_SCANNED || file.name.startsWith(".") || file.name == "__pycache__") return@forEach
                if (java.nio.file.Files.isSymbolicLink(file.toPath())) return@forEach
                if (file.isDirectory) visit(file, depth + 1)
                else if (file.isFile) files[file.relativeTo(directory).invariantSeparatorsPath] = file.lastModified() to file.length()
            }
        }
        visit(directory, 0)
        return files
    }

    fun changedFiles(directory: File, before: Map<String, Pair<Long, Long>>): List<File> =
        snapshot(directory).filter { (path, state) -> before[path] != state }.keys.map { File(directory, it) }
}

/** On-device Python with a per-chat working directory and namespace. */
class RunPythonTool(private val executor: PythonExecutor) : Tool {
    override val name = "run_python"
    override val description = "Run Python 3.13 on the phone. numpy and matplotlib are available (Agg backend; open figures are saved as PNG). " +
        "The working directory is this chat's folder; new or changed files there are shown to the user as cards. Variables persist between " +
        "calls in this chat until a timeout restarts Python. No network access: use web_fetch for pages and pip_install for pure-Python packages."
    override val risk = Risk.SAFE
    override val kind = ToolKind.COMPUTE
    override val schema = objectSchema(mapOf(
        "code" to stringSchema("Python source; print results or end with an expression"),
        "timeout_s" to buildJsonObject { put("type", "integer"); put("description", "Time limit in seconds, 1-110, default 60") },
    ), listOf("code"))

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult =
        PythonTools.execute(executor, args.stringArg("code"), args.intArg("timeout_s", 60, 1..PythonTools.MAX_TIMEOUT_SECONDS), ctx)
}

/** Adds pure-Python packages to the private site folder after the user approves the download. */
class PipInstallTool(private val installer: PackageInstaller) : Tool {
    override val name = "pip_install"
    override val description = "Install pure-Python packages (py3-none-any wheels from PyPI) and their dependencies into the app's private Python " +
        "folder, for run_python. Asks the user first. Packages with native code cannot be installed; numpy and matplotlib are built in."
    override val risk = Risk.CONFIRM
    override val kind = ToolKind.NETWORK
    override val schema = objectSchema(mapOf("packages" to buildJsonObject {
        put("type", "array")
        put("description", "1-10 requirement specifiers, e.g. \"tabulate\" or \"python-slugify==8.0.4\"")
        put("items", stringSchema("Requirement specifier"))
    }))

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        val packages = (args["packages"] as? JsonArray ?: throw IllegalArgumentException("packages must be a list")).map {
            (it as? JsonPrimitive)?.takeIf { value -> value.isString }?.content?.trim() ?: throw IllegalArgumentException("Each package must be text")
        }
        require(packages.size in 1..10) { "Install 1 to 10 packages at a time" }
        val report = installer.install(packages)
        return ToolResult(report.summary())
    }
}
