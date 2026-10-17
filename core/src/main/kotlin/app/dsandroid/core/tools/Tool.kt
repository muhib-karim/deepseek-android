package app.dsandroid.core.tools

import app.dsandroid.core.guard.WorkspacePath
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.StandardOpenOption

enum class Risk { SAFE, CONFIRM, DANGEROUS }
enum class ToolKind { READ, WRITE, NETWORK, SHELL, INSTALL, ACTION }

@Serializable
data class Artifact(val fileName: String, val mimeType: String, val path: String)

@Serializable
data class ToolResult(val content: String, val isError: Boolean = false, val artifacts: List<Artifact> = emptyList())

data class ToolContext(val workspace: WorkspacePath)

interface Tool {
    val name: String
    val description: String
    val schema: JsonObject
    val risk: Risk
    val kind: ToolKind
    suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult
}

fun JsonObject.stringArg(name: String): String {
    val value = this[name] as? JsonPrimitive
    require(value != null && value.isString) { "Missing or invalid $name" }
    return value.content
}

fun objectSchema(properties: Map<String, JsonObject>, required: List<String> = properties.keys.toList()): JsonObject = buildJsonObject {
    put("type", "object")
    put("properties", JsonObject(properties))
    put("required", JsonArray(required.map(::JsonPrimitive)))
    put("additionalProperties", false)
}

fun stringSchema(description: String): JsonObject = buildJsonObject {
    put("type", "string"); put("description", description)
}

/** New files only: a model cannot silently replace an existing artifact. */
fun writeArtifact(ctx: ToolContext, fileName: String, extension: String, mime: String, bytes: ByteArray): ToolResult {
    require(fileName.endsWith(extension, ignoreCase = true)) { "Expected a $extension file" }
    require(bytes.size <= 2 * 1024 * 1024) { "Artifact exceeds 2 MB" }
    val file = ctx.workspace.resolve(fileName)
    require(file != ctx.workspace.root && file.parentFile == ctx.workspace.root) { "Use a simple file name in the workspace" }
    check(ctx.workspace.root.isDirectory || ctx.workspace.root.mkdirs()) { "Cannot create workspace" }
    Files.write(file.toPath(), bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
    val artifact = Artifact(file.name, mime, ctx.workspace.relative(file))
    return ToolResult("Created ${file.name}", artifacts = listOf(artifact))
}
