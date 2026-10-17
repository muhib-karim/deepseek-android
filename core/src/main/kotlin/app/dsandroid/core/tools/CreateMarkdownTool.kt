package app.dsandroid.core.tools

import kotlinx.serialization.json.JsonObject

class CreateMarkdownTool : Tool {
    override val name = "create_markdown"
    override val description = "Create a new Markdown artifact in the workspace."
    override val risk = Risk.SAFE
    override val kind = ToolKind.WRITE
    override val schema = objectSchema(mapOf("fileName" to stringSchema("A new file ending in .md"), "content" to stringSchema("Markdown text")))
    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult =
        writeArtifact(ctx, args.stringArg("fileName"), ".md", "text/markdown", args.stringArg("content").toByteArray(Charsets.UTF_8))
}
