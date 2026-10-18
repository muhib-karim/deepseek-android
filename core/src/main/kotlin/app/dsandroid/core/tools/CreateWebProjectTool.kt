package app.dsandroid.core.tools

import app.dsandroid.core.artifacts.ArtifactStore
import app.dsandroid.core.artifacts.WebProject
import kotlinx.serialization.json.*

class CreateWebProjectTool : Tool {
    override val name = "create_web_project"
    override val description = "Create a multi-file website or HTML slide deck in the artifact canvas. Include index.html and up to 49 CSS, classic JS, SVG or JSON files. Use quoted relative src/href and CSS url references. Preview is offline: no fetch, imports, forms, navigation or remote resources. Export preserves every file in a ZIP."
    override val kind = ToolKind.WRITE
    override val risk = Risk.SAFE
    override val schema = objectSchema(mapOf(
        "title" to stringSchema("Simple new artifact name"),
        "kind" to buildJsonObject { put("type", "string"); put("enum", JsonArray(listOf("website", "slides").map(::JsonPrimitive))) },
        "files" to arraySchema(objectSchema(mapOf("path" to stringSchema("Relative path, including index.html"), "content" to stringSchema("UTF-8 source")))),
    ))
    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        val entries = args.arrayArg("files")
        require(entries.size in 1..50)
        val pairs = entries.map { it.jsonObject.let { file -> file.stringArg("path") to file.stringArg("content") } }
        require(pairs.map { it.first }.distinct().size == pairs.size)
        val project = WebProject(args.stringArg("kind"), pairs.toMap())
        val artifact = ArtifactStore(ctx.workspace).create("webproject", args.stringArg("title"), project.encode())
        return ToolResult("Created ${artifact.fileName}", artifacts = listOf(artifact))
    }
}
