package app.dsandroid.core.tools

import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.JsonObject
import kotlin.coroutines.coroutineContext

class ListFilesTool : Tool {
    override val name = "list_files"
    override val description = "List up to 200 entries in an artifact workspace directory."
    override val risk = Risk.SAFE
    override val kind = ToolKind.READ
    override val schema = objectSchema(mapOf("path" to stringSchema("Relative directory; defaults to .")), emptyList())
    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        coroutineContext.ensureActive()
        val directory = ctx.workspace.resolve(if (args.containsKey("path")) args.stringArg("path") else ".")
        require(directory.isDirectory) { "Directory does not exist" }
        val entries = directory.listFiles().orEmpty().filter {
            runCatching { ctx.workspace.resolve(ctx.workspace.relative(it)) == it.canonicalFile }.getOrDefault(false) && !java.nio.file.Files.isSymbolicLink(it.toPath())
        }.sortedBy { it.name }.take(200)
        return ToolResult(entries.joinToString("\n") { ctx.workspace.relative(it) + if (it.isDirectory) "/" else "" }.ifEmpty { "Workspace is empty" })
    }
}

class ReadFileTool : Tool {
    override val name = "read_file"
    override val description = "Read a UTF-8 text artifact, at most 2 MB, inside the workspace."
    override val risk = Risk.SAFE
    override val kind = ToolKind.READ
    override val schema = objectSchema(mapOf("path" to stringSchema("Relative artifact path")))
    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        coroutineContext.ensureActive()
        val file = ctx.workspace.resolve(args.stringArg("path"))
        require(file.isFile && file.length() <= 2 * 1024 * 1024) { "File missing or exceeds 2 MB" }
        val bytes = file.inputStream().use { stream ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                coroutineContext.ensureActive()
                val count = stream.read(buffer)
                if (count == -1) break
                require(output.size() + count <= 2 * 1024 * 1024) { "File exceeds 2 MB" }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        require(!bytes.contains(0.toByte())) { "Only text files can be read" }
        return ToolResult(bytes.toString(Charsets.UTF_8))
    }
}
