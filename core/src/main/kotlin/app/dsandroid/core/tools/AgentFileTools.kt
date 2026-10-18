package app.dsandroid.core.tools

import app.dsandroid.core.document.CsvWriter
import app.dsandroid.core.document.DocxWriter
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*
import kotlin.coroutines.coroutineContext

object MimeTypes {
    fun forName(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "pdf" -> "application/pdf"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "svg" -> "image/svg+xml"
        "csv" -> "text/csv"
        "md", "markdown" -> "text/markdown"
        "html", "htm" -> "text/html"
        "json" -> "application/json"
        "docx" -> DocxWriter.MIME
        "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        "zip" -> "application/zip"
        "txt", "py", "log", "tsv", "xml", "yaml", "yml", "js", "css", "kt", "toml", "ini" -> "text/plain"
        else -> "application/octet-stream"
    }
}

/** The chat's private folder; agent tools never leave it. */
fun ToolContext.chatRoot(): File {
    val root = if (chatDir.isEmpty()) workspace.root else workspace.resolve(chatDir)
    check(root.isDirectory || root.mkdirs()) { "Cannot create the chat folder" }
    return root
}

/** Resolves a model-supplied relative path inside the chat folder; absolute paths and traversal are refused. */
fun ToolContext.chatFile(path: String): File {
    val clean = path.trim().removePrefix("./")
    require(clean.isNotEmpty() && !clean.startsWith("/")) { "Use a relative path inside this chat's folder" }
    val root = chatRoot()
    val file = workspace.resolve(if (chatDir.isEmpty()) clean else "$chatDir/$clean")
    require(file.toPath().startsWith(root.toPath()) && file != root) { "Path is outside this chat's folder" }
    return file
}

/** Path shown to the model: relative to the chat folder. */
fun ToolContext.chatPath(file: File): String = chatRoot().toPath().relativize(file.canonicalFile.toPath()).toString().replace(File.separatorChar, '/')

/** Artifacts keep workspace-relative paths so every viewer resolves them the same way. */
fun ToolContext.artifactFor(file: File): Artifact = Artifact(file.name, MimeTypes.forName(file.name), workspace.relative(file))

/** Next free name (report.pdf, report-2.pdf, ...) so generated files never overwrite earlier cards. */
fun uniqueFile(file: File): File {
    if (!file.exists()) return file
    val base = file.nameWithoutExtension
    val extension = file.extension.let { if (it.isEmpty()) "" else ".$it" }
    for (index in 2..999) {
        val candidate = File(file.parentFile, "$base-$index$extension")
        if (!candidate.exists()) return candidate
    }
    throw IllegalArgumentException("Too many files named ${file.name}")
}

fun writeNewFile(file: File, bytes: ByteArray) {
    check(file.parentFile.isDirectory || file.parentFile.mkdirs()) { "Cannot create the folder" }
    Files.write(file.toPath(), bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
}

private fun replaceFile(file: File, bytes: ByteArray) {
    check(file.parentFile.isDirectory || file.parentFile.mkdirs()) { "Cannot create the folder" }
    val temporary = File(file.parentFile, ".${file.name}.writing")
    temporary.writeBytes(bytes)
    Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
}

/** Lenient integer argument: models sometimes send 10.0 for 10. */
fun JsonObject.intArg(name: String, default: Int, range: IntRange): Int {
    val value = this[name] ?: return default
    if (value is JsonNull) return default
    val primitive = value as? JsonPrimitive
    val number = primitive?.takeIf { !it.isString }?.let { it.intOrNull ?: it.doubleOrNull?.takeIf { d -> d % 1.0 == 0.0 }?.toInt() }
        ?: throw IllegalArgumentException("$name must be a whole number")
    require(number in range) { "$name must be between ${range.first} and ${range.last}" }
    return number
}

fun JsonObject.optionalString(name: String): String? = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content

/** dsh `write`: create or fully replace a UTF-8 text file. */
class WriteTool : Tool {
    override val name = "write"
    override val description = "Create or fully replace a UTF-8 text file in this chat's folder (2 MB maximum). Files are private until shown with present."
    override val risk = Risk.SAFE
    override val kind = ToolKind.WRITE
    override val schema = objectSchema(mapOf(
        "file_path" to stringSchema("Relative path in this chat's folder, e.g. notes/plan.md"),
        "content" to stringSchema("Full UTF-8 text content"),
    ))

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        coroutineContext.ensureActive()
        val file = ctx.chatFile(args.stringArg("file_path"))
        require(!file.isDirectory) { "That path is a folder" }
        val bytes = args.stringArg("content").toByteArray(Charsets.UTF_8)
        require(bytes.size <= 2 * 1024 * 1024) { "Content exceeds 2 MB" }
        val existed = file.exists()
        replaceFile(file, bytes)
        return ToolResult("${if (existed) "Replaced" else "Created"} ${ctx.chatPath(file)} (${bytes.size} bytes)")
    }
}

/** dsh `read`: line-numbered UTF-8 text. */
class ReadTool : Tool {
    override val name = "read"
    override val description = "Read a UTF-8 text file from this chat's folder and return line-numbered content."
    override val risk = Risk.SAFE
    override val kind = ToolKind.READ
    override val schema = objectSchema(mapOf(
        "file_path" to stringSchema("Relative path in this chat's folder"),
        "offset" to buildJsonObject { put("type", "integer"); put("description", "1-based first line, default 1") },
        "limit" to buildJsonObject { put("type", "integer"); put("description", "Maximum lines, default 2000") },
    ), listOf("file_path"))

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        coroutineContext.ensureActive()
        val file = ctx.chatFile(args.stringArg("file_path"))
        require(file.isFile) { "File not found: ${args.stringArg("file_path")}" }
        require(file.length() <= 2 * 1024 * 1024) { "File exceeds 2 MB" }
        val bytes = file.readBytes()
        require(!bytes.contains(0.toByte())) { "Only text files can be read" }
        val lines = bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF").split('\n').map { it.removeSuffix("\r") }
        val offset = args.intArg("offset", 1, 1..Int.MAX_VALUE)
        val limit = args.intArg("limit", 2000, 1..10_000)
        val slice = lines.drop(offset - 1).take(limit)
        val text = slice.mapIndexed { index, line -> "%6d\t%s".format(offset + index, line.take(4000)) }.joinToString("\n")
        val remaining = lines.size - (offset - 1) - slice.size
        return ToolResult(when {
            slice.isEmpty() -> "No lines at offset $offset; the file has ${lines.size} lines."
            remaining > 0 -> "$text\n[$remaining more lines; continue with offset ${offset + slice.size}]"
            else -> text
        })
    }
}

/** dsh `present`: declare existing files as deliverables, shown as cards. */
class PresentTool : Tool {
    override val name = "present"
    override val description = "Declare existing files in this chat's folder as deliverables for the user (at most 4 per call). They appear as file cards with preview, open, save and share."
    override val risk = Risk.SAFE
    override val kind = ToolKind.READ
    override val schema = objectSchema(mapOf("files" to buildJsonObject {
        put("type", "array")
        put("description", "Usually the 1-2 most important deliverables; at most 4")
        put("items", objectSchema(mapOf(
            "path" to stringSchema("Relative path of an existing file"),
            "description" to stringSchema("Brief description for the user"),
        ), listOf("path")))
    }))

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        val files = args.arrayArg("files")
        require(files.size in 1..4) { "Present 1 to 4 files" }
        val artifacts = files.map { item ->
            val entry = item as? JsonObject ?: throw IllegalArgumentException("Each file needs a path")
            val file = ctx.chatFile(entry.stringArg("path"))
            require(file.isFile) { "File not found: ${entry.stringArg("path")}" }
            ctx.artifactFor(file)
        }.distinctBy { it.path }
        return ToolResult("Presented ${artifacts.joinToString { it.fileName }}", artifacts = artifacts)
    }
}

/** Typed documents for the user: DOCX from Markdown, normalized CSV, or plain text formats. */
class WriteFileTool : Tool {
    override val name = "write_file"
    override val description = "Create a document the user can open, shown as a file card: .docx (content is Markdown: headings, lists, tables, code), .csv (CSV text), .md, .txt, .html or .json. Use create_pdf for PDF and run_python with matplotlib for charts or images."
    override val risk = Risk.SAFE
    override val kind = ToolKind.WRITE
    override val schema = objectSchema(mapOf(
        "file_path" to stringSchema("New file name in this chat's folder, with extension"),
        "content" to stringSchema("Markdown for .docx, CSV text for .csv, otherwise the file text"),
        "title" to stringSchema("Optional document title for .docx"),
    ), listOf("file_path", "content"))

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        coroutineContext.ensureActive()
        val requested = ctx.chatFile(args.stringArg("file_path"))
        val content = args.stringArg("content")
        val bytes = when (requested.extension.lowercase()) {
            "docx" -> DocxWriter.build(args.optionalString("title") ?: requested.nameWithoutExtension, content)
            "csv" -> CsvWriter.normalize(content).toByteArray(Charsets.UTF_8)
            "md", "markdown", "txt", "html", "htm" -> content.toByteArray(Charsets.UTF_8)
            "json" -> {
                Json.parseToJsonElement(content)
                content.toByteArray(Charsets.UTF_8)
            }
            "pdf" -> throw IllegalArgumentException("Use create_pdf for PDF files")
            else -> throw IllegalArgumentException("Unsupported type; use .docx, .csv, .md, .txt, .html or .json")
        }
        require(bytes.size <= DocxWriter.MAX_BYTES) { "File exceeds 10 MB" }
        val file = uniqueFile(requested)
        writeNewFile(file, bytes)
        return ToolResult("Created ${ctx.chatPath(file)} (${bytes.size} bytes)", artifacts = listOf(ctx.artifactFor(file)))
    }
}
