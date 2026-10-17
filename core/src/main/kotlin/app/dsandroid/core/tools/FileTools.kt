package app.dsandroid.core.tools

import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*
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
    override val schema = objectSchema(mapOf("path" to stringSchema("Relative artifact path"), "max_chars" to buildJsonObject { put("type","integer"); put("minimum",1); put("maximum",200000) }),listOf("path"))
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
        val maxChars = if (args.containsKey("max_chars")) (args["max_chars"] as? JsonPrimitive)?.intOrNull ?: throw IllegalArgumentException("Invalid max_chars") else 200000
        return ToolResult(app.dsandroid.core.attachments.PlainTextAttachment.cap(bytes.toString(Charsets.UTF_8),maxChars))
    }
}

class ListDirTool : Tool by ListFilesTool() { override val name = "list_dir" }
class CreateFileTool : Tool {
    override val name = "create_file"; override val description = "Create a new UTF-8 text file inside the workspace (2 MB maximum)."
    override val risk = Risk.SAFE; override val kind = ToolKind.WRITE
    override val schema = objectSchema(mapOf("path" to stringSchema("Relative new path"), "content" to stringSchema("Text content")))
    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        coroutineContext.ensureActive()
        val file = ctx.workspace.resolve(args.stringArg("path")); require(file != ctx.workspace.root)
        val bytes = args.stringArg("content").toByteArray(); require(bytes.size <= 2 * 1024 * 1024)
        check(file.parentFile.isDirectory || file.parentFile.mkdirs())
        java.nio.file.Files.write(file.toPath(),bytes,java.nio.file.StandardOpenOption.CREATE_NEW)
        return ToolResult("Created ${ctx.workspace.relative(file)}",artifacts=listOf(Artifact(file.name,"text/plain",ctx.workspace.relative(file))))
    }
}
class ZipFilesTool : Tool {
    override val name = "zip_files"; override val description = "Zip workspace files or recursive directories; maximum 500 files and 16 MB expanded."
    override val risk = Risk.SAFE; override val kind = ToolKind.WRITE
    override val schema = objectSchema(mapOf("sources" to arraySchema(stringSchema("Workspace file or directory")),"zip_path" to stringSchema("New .zip path")))
    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        val target = ctx.workspace.resolve(args.stringArg("zip_path")); require(target.name.endsWith(".zip",true))
        val files = linkedMapOf<String,java.io.File>(); var total = 0L; var visited = 0
        fun visit(file: java.io.File) {
            require(++visited <= 1000 && ctx.workspace.relative(file).count { it == '/' } <= 32) { "Too many directories or excessive depth" }
            val safe = ctx.workspace.resolve(ctx.workspace.relative(file)); require(safe == file.canonicalFile && !java.nio.file.Files.isSymbolicLink(file.toPath()))
            require(file.exists() && file != target) { "Source missing or includes destination" }
            if (file.isDirectory) file.listFiles().orEmpty().sortedBy { it.name }.forEach(::visit)
            else { require(file.isFile); val key = ctx.workspace.relative(file); if (!files.containsKey(key)) { files[key]=file; total += file.length(); require(files.size <= 500 && total <= 16*1024*1024) { "Zip sources too large" } } }
        }
        args.arrayArg("sources").forEach { visit(ctx.workspace.resolve(it.textValue())) }; require(files.isNotEmpty())
        val output = java.io.ByteArrayOutputStream(); var copied = 0L
        java.util.zip.ZipOutputStream(output).use { zip -> files.forEach { (name,file) ->
            coroutineContext.ensureActive(); ctx.workspace.resolve(name)
            zip.putNextEntry(java.util.zip.ZipEntry(name)); file.inputStream().use { input -> val buffer = ByteArray(8192); while(true) { coroutineContext.ensureActive(); val n=input.read(buffer); if(n<0) break; copied+=n; require(copied <= 16*1024*1024); zip.write(buffer,0,n) } }; zip.closeEntry()
        } }
        require(output.size() <= 2*1024*1024) { "Zip exceeds 2 MB" }; check(target.parentFile.isDirectory || target.parentFile.mkdirs())
        java.nio.file.Files.write(target.toPath(),output.toByteArray(),java.nio.file.StandardOpenOption.CREATE_NEW)
        return ToolResult("Zipped ${files.size} files",artifacts=listOf(Artifact(target.name,"application/zip",ctx.workspace.relative(target))))
    }
}
class ListZipTool : Tool {
    override val name = "list_zip"; override val description = "List up to 500 ZIP entries without extracting files."
    override val risk = Risk.SAFE; override val kind = ToolKind.READ
    override val schema = objectSchema(mapOf("zip_path" to stringSchema("Workspace zip path")))
    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        val file=ctx.workspace.resolve(args.stringArg("zip_path")); require(file.isFile && file.length() <= 2*1024*1024)
        val names=mutableListOf<String>(); var expanded=0L
        java.util.zip.ZipInputStream(file.inputStream()).use { zip -> while(true) { coroutineContext.ensureActive(); val e=zip.nextEntry ?: break; require(names.size<500); ctx.workspace.resolve(e.name); names.add(e.name); val buffer=ByteArray(8192); while(true) { val n=zip.read(buffer); if(n<0) break; expanded+=n; require(expanded<=16*1024*1024) } } }
        return ToolResult(names.joinToString("\n"))
    }
}
