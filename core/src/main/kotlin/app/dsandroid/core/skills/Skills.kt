package app.dsandroid.core.skills

import app.dsandroid.core.python.PythonExecutor
import app.dsandroid.core.python.PythonTools
import app.dsandroid.core.python.Pep440
import app.dsandroid.core.tools.*
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.ZipInputStream
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.*

@Serializable
data class SkillToolSpec(
    val name: String,
    val description: String,
    /** Function in main.py; defaults to [name]. */
    val function: String? = null,
    val parameters: JsonObject = buildJsonObject { put("type", "object"); put("properties", buildJsonObject { }) },
)

/** manifest.json of a skill pack. [pip] lists pure-Python requirements installed with the skill. */
@Serializable
data class SkillManifest(
    val name: String,
    val description: String,
    val version: String,
    val tools: List<SkillToolSpec> = emptyList(),
    val pip: List<String> = emptyList(),
)

object SkillManifestLoader {
    private val NAME = Regex("[a-z0-9][a-z0-9-]{0,29}")
    private val TOOL = Regex("[a-z][a-z0-9_]{0,29}")
    private val FUNCTION = Regex("[A-Za-z_][A-Za-z0-9_]{0,63}")
    private val VERSION = Regex("\\d+(\\.\\d+){0,3}([-+.][0-9A-Za-z.-]{1,32})?")
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(text: String): SkillManifest {
        val root = try { json.parseToJsonElement(text) as? JsonObject } catch (_: Exception) { null }
            ?: throw IllegalArgumentException("manifest.json is not a JSON object")
        listOf("name", "description", "version").forEach { key ->
            require((root[key] as? JsonPrimitive)?.isString == true) { "manifest.json needs a text \"$key\"" }
        }
        val manifest = try { json.decodeFromJsonElement(SkillManifest.serializer(), root) }
        catch (error: Exception) { throw IllegalArgumentException("manifest.json has an invalid field: ${error.message?.take(120)}") }
        validate(manifest)
        return manifest
    }

    fun validate(manifest: SkillManifest) {
        require(NAME.matches(manifest.name)) { "Skill name must be 1-30 lowercase letters, digits or hyphens" }
        require(manifest.description.isNotBlank() && manifest.description.length <= 500) { "Skill description must be 1-500 characters" }
        require(VERSION.matches(manifest.version)) { "Skill version must look like 1.0.0" }
        require(manifest.tools.size <= 16) { "A skill can declare at most 16 tools" }
        require(manifest.tools.map { it.name }.distinct().size == manifest.tools.size) { "Skill tool names must be unique" }
        manifest.tools.forEach { tool ->
            require(TOOL.matches(tool.name)) { "Tool name ${tool.name} must be 1-30 lowercase letters, digits or underscores" }
            require(tool.description.isNotBlank() && tool.description.length <= 1000) { "Tool ${tool.name} needs a description" }
            require(FUNCTION.matches(tool.function ?: tool.name)) { "Tool ${tool.name} has an invalid Python function name" }
            require((tool.parameters["type"] as? JsonPrimitive)?.contentOrNull == "object") { "Tool ${tool.name} parameters must be a JSON object schema" }
            require(tool.parameters.toString().length <= 8000) { "Tool ${tool.name} schema is too large" }
        }
        require(manifest.pip.size <= 20) { "A skill can require at most 20 packages" }
        manifest.pip.forEach { requirement ->
            val parsed = runCatching { Pep440.requirement(requirement) }.getOrNull()
            require(parsed != null && parsed.marker == null) { "Invalid pip requirement: $requirement" }
        }
    }
}

/** A validated pack in memory: manifest, SKILL.md instructions and every other file (main.py, data). */
class SkillPack(val manifest: SkillManifest, val instructions: String, val files: Map<String, ByteArray>)

object SkillPackReader {
    const val MAX_ARCHIVE_BYTES = 5 * 1024 * 1024
    private const val MAX_FILES = 64
    private const val MAX_TOTAL_BYTES = 8L * 1024 * 1024
    private val SAFE_PATH = Regex("[A-Za-z0-9._-]+(/[A-Za-z0-9._-]+){0,5}")

    fun fromZip(bytes: ByteArray): SkillPack {
        require(bytes.size <= MAX_ARCHIVE_BYTES) { "Skill archive exceeds 5 MB" }
        val files = linkedMapOf<String, ByteArray>()
        var total = 0L
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory || entry.name.startsWith("__MACOSX/") || entry.name.substringAfterLast('/').startsWith(".")) continue
                require(files.size < MAX_FILES) { "Skill archive has too many files" }
                val content = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = zip.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= MAX_TOTAL_BYTES) { "Skill archive expands beyond 8 MB" }
                    content.write(buffer, 0, count)
                }
                files[entry.name] = content.toByteArray()
            }
        }
        return fromFiles(stripCommonFolder(files))
    }

    fun fromDirectory(directory: File): SkillPack {
        require(directory.isDirectory) { "Skill folder not found" }
        val files = linkedMapOf<String, ByteArray>()
        var total = 0L
        directory.walkTopDown().onEnter { !it.name.startsWith(".") || it == directory }.filter { it.isFile && !it.name.startsWith(".") }.forEach { file ->
            require(!Files.isSymbolicLink(file.toPath())) { "Skill folders cannot contain links" }
            require(files.size < MAX_FILES) { "Skill folder has too many files" }
            total += file.length()
            require(total <= MAX_TOTAL_BYTES) { "Skill folder exceeds 8 MB" }
            files[file.relativeTo(directory).invariantSeparatorsPath] = file.readBytes()
        }
        return fromFiles(files)
    }

    fun fromFiles(files: Map<String, ByteArray>): SkillPack {
        files.keys.forEach { path -> require(SAFE_PATH.matches(path) && path.split('/').none { it == "." || it == ".." }) { "Unsafe file name in skill: $path" } }
        val manifest = SkillManifestLoader.parse(files["manifest.json"]?.toString(Charsets.UTF_8) ?: throw IllegalArgumentException("Skill needs manifest.json"))
        val instructions = files["SKILL.md"]?.toString(Charsets.UTF_8) ?: throw IllegalArgumentException("Skill needs SKILL.md")
        require(instructions.isNotBlank() && instructions.length <= 64_000) { "SKILL.md must contain 1-64000 characters" }
        if (manifest.tools.isNotEmpty()) {
            val main = files["main.py"] ?: throw IllegalArgumentException("Skill tools need main.py")
            require(main.size <= 256 * 1024) { "main.py exceeds 256 KB" }
        }
        return SkillPack(manifest, instructions, files)
    }

    /** Zipping a folder usually adds one top-level directory; accept that layout too. */
    private fun stripCommonFolder(files: Map<String, ByteArray>): Map<String, ByteArray> {
        if ("manifest.json" in files) return files
        val prefix = files.keys.firstOrNull()?.substringBefore('/', "")?.takeIf { it.isNotEmpty() } ?: return files
        if (files.keys.any { !it.startsWith("$prefix/") }) return files
        return files.mapKeys { it.key.removePrefix("$prefix/") }
    }
}

data class SkillEntry(val manifest: SkillManifest, val directory: File, val builtIn: Boolean, val enabled: Boolean) {
    val mainPy: File get() = File(directory, "main.py")
}

/**
 * Installed skills under [root]: built-ins are copied from app assets into builtin/, user packs live in
 * installed/. Enabled state persists in state.json; built-ins start enabled, installs are enabled after
 * the user confirms them.
 */
class SkillStore(private val root: File) {
    private val json = Json { ignoreUnknownKeys = true }
    private val builtinDir get() = File(root, "builtin")
    private val installedDir get() = File(root, "installed")
    private val stateFile get() = File(root, "state.json")

    @Synchronized fun syncBuiltIns(packs: List<SkillPack>) {
        val names = packs.map { it.manifest.name }.toSet()
        builtinDir.listFiles().orEmpty().filter { it.isDirectory && it.name !in names }.forEach { it.deleteRecursively() }
        packs.forEach { pack ->
            val target = File(builtinDir, pack.manifest.name)
            val current = runCatching { SkillManifestLoader.parse(File(target, "manifest.json").readText()) }.getOrNull()
            if (current == null || current.version != pack.manifest.version || !File(target, "SKILL.md").isFile) write(pack, target)
        }
    }

    @Synchronized fun install(pack: SkillPack): SkillEntry {
        val name = pack.manifest.name
        require(!File(builtinDir, name).isDirectory) { "A built-in skill is already named $name" }
        write(pack, File(installedDir, name))
        setEnabled(name, true)
        return list().first { it.manifest.name == name }
    }

    @Synchronized fun remove(name: String): Boolean {
        require(!File(builtinDir, name).isDirectory) { "Built-in skills can be disabled but not removed" }
        val directory = File(installedDir, name)
        if (!directory.isDirectory) return false
        directory.deleteRecursively()
        writeState(readState() - name)
        return true
    }

    @Synchronized fun setEnabled(name: String, enabled: Boolean) {
        require(File(builtinDir, name).isDirectory || File(installedDir, name).isDirectory) { "Unknown skill $name" }
        writeState(readState() + (name to enabled))
    }

    @Synchronized fun list(): List<SkillEntry> {
        val state = readState()
        fun load(directory: File, builtIn: Boolean): List<SkillEntry> = directory.listFiles().orEmpty().filter { it.isDirectory }.sortedBy { it.name }.mapNotNull { folder ->
            runCatching { SkillManifestLoader.parse(File(folder, "manifest.json").readText()) }.getOrNull()
                ?.takeIf { it.name == folder.name }
                ?.let { manifest -> SkillEntry(manifest, folder, builtIn, state[manifest.name] ?: builtIn) }
        }
        return load(builtinDir, true) + load(installedDir, false)
    }

    fun enabled(): List<SkillEntry> = list().filter { it.enabled }

    fun instructions(entry: SkillEntry): String = File(entry.directory, "SKILL.md").readText()

    private fun write(pack: SkillPack, target: File) {
        val staging = File(target.parentFile, ".${target.name}-${System.nanoTime()}")
        try {
            pack.files.forEach { (path, bytes) ->
                val file = File(staging, path)
                check(file.parentFile.isDirectory || file.parentFile.mkdirs()) { "Cannot write the skill" }
                file.writeBytes(bytes)
            }
            if (target.exists()) check(target.deleteRecursively()) { "Cannot replace the skill" }
            Files.move(staging.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } finally {
            staging.deleteRecursively()
        }
    }

    private fun readState(): Map<String, Boolean> = runCatching {
        json.decodeFromString(MapSerializer(String.serializer(), Boolean.serializer()), stateFile.readText())
    }.getOrDefault(emptyMap())

    private fun writeState(state: Map<String, Boolean>) {
        check(root.isDirectory || root.mkdirs())
        val temporary = File(root, "state.json.tmp")
        temporary.writeText(json.encodeToString(MapSerializer(String.serializer(), Boolean.serializer()), state))
        Files.move(temporary.toPath(), stateFile.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
}

/** System-prompt lines for enabled skills: name and description only; instructions load on demand. */
object SkillPrompt {
    fun summaries(skills: List<SkillEntry>): List<Pair<String, String>> = skills.map { it.manifest.name to it.manifest.description }

    fun section(skills: List<SkillEntry>): String = if (skills.isEmpty()) "" else
        "\n\nSkills (call the skill tool with the exact name to load full instructions before using one):\n" +
            skills.joinToString("\n") { "- ${it.manifest.name}: ${it.manifest.description}" }
}

/** dsh `skill`: load an enabled skill's instructions by name. */
class SkillTool(private val skills: () -> List<SkillEntry>, private val read: (SkillEntry) -> String) : Tool {
    override val name = "skill"
    override val description = "Load the full instructions for a skill. Call it before acting on a task that names or clearly matches an available skill."
    override val risk = Risk.SAFE
    override val kind = ToolKind.READ
    override val schema = objectSchema(mapOf("name" to stringSchema("The exact skill name from the available skills list")))

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        val requested = args.stringArg("name").trim()
        val available = skills()
        val entry = available.firstOrNull { it.manifest.name == requested }
            ?: return ToolResult("Unknown or disabled skill \"$requested\". Available: ${available.joinToString { it.manifest.name }.ifEmpty { "none" }}", isError = true)
        val tools = entry.manifest.tools.map { SkillFunctionTool.toolName(entry.manifest, it) }
        return ToolResult(buildString {
            append("Skill ${entry.manifest.name} ${entry.manifest.version}\n")
            if (tools.isNotEmpty()) append("Tools from this skill: ${tools.joinToString()}\n")
            append('\n')
            append(read(entry).take(64_000))
        })
    }
}

/** A function from a skill's main.py, run in the chat folder by the on-device interpreter. */
class SkillFunctionTool(private val entry: SkillEntry, private val spec: SkillToolSpec, private val executor: PythonExecutor) : Tool {
    companion object {
        fun toolName(manifest: SkillManifest, spec: SkillToolSpec) = "${manifest.name.replace('-', '_')}__${spec.name}"

        /** Python that imports main.py by path and calls one function with JSON arguments; JSON strings are valid Python literals. */
        fun harness(mainPy: File, module: String, function: String, arguments: JsonObject): String {
            require(Regex("[A-Za-z_][A-Za-z0-9_]{0,63}").matches(function) && Regex("[A-Za-z_][A-Za-z0-9_]{0,63}").matches(module)) { "Invalid Python name" }
            val path = JsonPrimitive(mainPy.absolutePath).toString()
            val encoded = JsonPrimitive(arguments.toString()).toString()
            return listOf(
                "import importlib.util as _skill_util, json as _skill_json, sys as _skill_sys",
                "_skill_spec = _skill_util.spec_from_file_location(\"$module\", $path)",
                "_skill_module = _skill_util.module_from_spec(_skill_spec)",
                "_skill_sys.modules[\"$module\"] = _skill_module",
                "_skill_spec.loader.exec_module(_skill_module)",
                "_skill_result = _skill_module.$function(**_skill_json.loads($encoded))",
                "print(_skill_result if isinstance(_skill_result, str) else _skill_json.dumps(_skill_result, ensure_ascii=False, default=str))",
            ).joinToString("\n")
        }
    }

    override val name = toolName(entry.manifest, spec)
    override val description = "[${entry.manifest.name} skill] ${spec.description}"
    override val risk = Risk.SAFE
    override val kind = ToolKind.COMPUTE
    override val schema = spec.parameters

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        require(entry.mainPy.isFile) { "The ${entry.manifest.name} skill has no main.py" }
        val code = harness(entry.mainPy, "skill_" + entry.manifest.name.replace('-', '_'), spec.function ?: spec.name, args)
        return PythonTools.execute(executor, code, 90, ctx)
    }
}
