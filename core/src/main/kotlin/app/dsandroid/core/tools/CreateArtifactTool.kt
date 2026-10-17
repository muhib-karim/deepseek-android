package app.dsandroid.core.tools

import app.dsandroid.core.artifacts.ArtifactStore
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class CreateArtifactTool : Tool {
    override val name = "create_artifact"
    override val description = "Create a new private artifact canvas: HTML, static SVG, Mermaid, Markdown, CSV, text or JSON. HTML can run JavaScript in an isolated preview; network starts blocked. Never overwrites an existing file."
    override val risk = Risk.SAFE
    override val kind = ToolKind.WRITE
    override val schema = objectSchema(mapOf(
        "title" to stringSchema("Simple new file title or name, using letters, digits, spaces, hyphens, underscores and dots"),
        "format" to buildJsonObject { put("type", "string"); put("enum", JsonArray(listOf("html", "svg", "mermaid", "markdown", "csv", "txt", "json").map(::JsonPrimitive))) },
        "content" to stringSchema("Complete UTF-8 artifact source, at most 2 MB")
    ))
    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        val artifact = ArtifactStore(ctx.workspace).create(args.stringArg("format"), args.stringArg("title"), args.stringArg("content"))
        return ToolResult("Created ${artifact.fileName}", artifacts = listOf(artifact))
    }
}
