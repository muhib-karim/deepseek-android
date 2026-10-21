package app.dsandroid.core.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/** Which line icon a tool step card shows. */
enum class ToolStepKind { SEARCH, WEB, CODE, FILE, READ, OTHER }

/**
 * Human titles for tool step cards, as chat agents show them: "Searched the web: <query>", "Read <host>", "Ran JavaScript",
 * "Created report.pdf". A running step reads in the present ("Searching the web: …"); never raw JSON.
 */
object ToolStepTitle {
    private val fileTools = setOf("write_file", "write", "create_file", "zip_files", "create_artifact", "create_web_project")

    fun title(step: TurnStep.ToolCall): String = title(step.name, step.argsJson, step.status, step.resultPreview)

    fun title(name: String, argsJson: String, status: ToolStatus, resultPreview: String = ""): String {
        val args = runCatching { Json.parseToJsonElement(argsJson).jsonObject }.getOrNull()
        fun arg(vararg keys: String) = keys.firstNotNullOfOrNull { key -> (args?.get(key) as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } }.orEmpty()
        val running = status == ToolStatus.RUNNING || status == ToolStatus.WAITING
        fun verb(present: String, past: String) = if (running) present else past
        val created = if (status == ToolStatus.DONE) Regex("^Created (\\S+)").find(resultPreview.lineSequence().firstOrNull().orEmpty())?.groupValues?.get(1) else null
        val file = (created ?: arg("file_path", "fileName", "path", "zip_path", "name")).substringAfterLast('/')
        return when {
            name == "web_search" -> verb("Searching the web", "Searched the web") + arg("query").let { if (it.isBlank()) "" else ": $it" }
            name == "web_fetch" || name == "visit_web" -> verb("Reading", "Read") + " " + host(arg("url")).ifBlank { "a web page" }
            name == "code_interpreter" -> verb("Running", "Ran") + " " + if (arg("language").equals("python", true)) "Python" else "JavaScript"
            name == "run_python" -> verb("Running", "Ran") + " Python"
            name == "pip_install" -> verb("Installing", "Installed") + " " + ((args?.get("packages") as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }?.joinToString().orEmpty().ifBlank { "packages" })
            name == "read" || name == "read_file" -> verb("Reading", "Read") + " " + file.ifBlank { "a file" }
            name in setOf("list_files", "list_dir", "list_zip") -> verb("Browsing", "Browsed") + " files"
            name == "present" -> verb("Presenting", "Presented") + " files"
            name == "skill" -> verb("Loading", "Loaded") + " skill " + arg("name")
            name == "remember" -> verb("Saving", "Saved") + " to memory"
            name == "render_chart" -> verb("Rendering", "Rendered") + " a chart"
            name.startsWith("create_") || name in fileTools -> verb("Creating", "Created") + " " + file.ifBlank { name.removePrefix("create_").replace('_', ' ') }
            "__" in name -> verb("Running", "Ran") + " " + name.substringBefore("__").replace('_', '-') + " · " + name.substringAfter("__")
            else -> name.replace('_', ' ').replaceFirstChar { it.uppercase() }
        }.trim()
    }

    fun kind(name: String): ToolStepKind = when {
        name == "web_search" -> ToolStepKind.SEARCH
        name == "web_fetch" || name == "visit_web" || name.startsWith("browser_") -> ToolStepKind.WEB
        name in setOf("code_interpreter", "run_python", "pip_install", "render_chart") || "__" in name -> ToolStepKind.CODE
        name in setOf("read", "read_file", "list_files", "list_dir", "list_zip") -> ToolStepKind.READ
        name.startsWith("create_") || name in fileTools || name == "present" -> ToolStepKind.FILE
        else -> ToolStepKind.OTHER
    }

    /** Arguments as readable lines: code as-is, other values one "key: value" per line, bounded. */
    fun arguments(argsJson: String, limit: Int = 4000): String {
        val args = runCatching { Json.parseToJsonElement(argsJson) as? JsonObject }.getOrNull() ?: return truncate(argsJson, limit)
        (args["code"] as? JsonPrimitive)?.contentOrNull?.let { return truncate(it, limit) }
        return truncate(args.entries.joinToString("\n") { (key, value) -> "$key: ${(value as? JsonPrimitive)?.contentOrNull ?: value.toString()}" }, limit)
    }

    fun truncate(text: String, limit: Int): String = if (text.length <= limit) text else text.take(limit) + "\n[… ${text.length - limit} more characters]"

    private fun host(url: String) = runCatching { java.net.URI(url).host }.getOrNull()?.removePrefix("www.")
        ?: url.substringAfter("://").substringBefore('/')
}
