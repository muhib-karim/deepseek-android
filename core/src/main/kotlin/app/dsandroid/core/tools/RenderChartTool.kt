package app.dsandroid.core.tools

import kotlinx.serialization.json.*

/** The existing deterministic SVG renderer also serves the inline chart tool. */
class RenderChartTool : Tool {
    override val name = "render_chart"
    override val description = "Render data as an inline SVG chart. Supports bar, line and pie; pie needs one nonnegative series."
    override val risk = Risk.SAFE
    override val kind = ToolKind.WRITE
    override val schema = objectSchema(mapOf(
        "data" to objectSchema(mapOf(
            "labels" to arraySchema(stringSchema("Category label")),
            "series" to arraySchema(objectSchema(mapOf(
                "name" to stringSchema("Series legend"),
                "values" to arraySchema(buildJsonObject { put("type", "number") }),
            ))),
        )),
        "spec" to objectSchema(mapOf(
            "type" to buildJsonObject { put("type", "string"); put("enum", JsonArray(listOf("bar", "line", "pie").map(::JsonPrimitive))) },
            "title" to stringSchema("Chart title"),
        )),
        "fileName" to stringSchema("Optional new .svg filename"),
    ), listOf("data", "spec"))

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        require(args.keys.all { it in setOf("data", "spec", "fileName") }) { "Unknown chart argument" }
        val data = args["data"] as? JsonObject ?: throw IllegalArgumentException("Missing chart data")
        val spec = args["spec"] as? JsonObject ?: throw IllegalArgumentException("Missing chart spec")
        val labels = data.arrayArg("labels").map { it.textValue() }
        val series = data.arrayArg("series").map { entry ->
            val item = entry as? JsonObject ?: throw IllegalArgumentException("Expected chart series")
            ChartSeries(item.stringArg("name"), item.arrayArg("values").map { value ->
                require(value is JsonPrimitive && !value.isString) { "Expected numeric value" }
                value.doubleOrNull ?: throw IllegalArgumentException("Expected numeric value")
            })
        }
        val svg = ChartSvgBuilder.build(spec.stringArg("type"), spec.stringArg("title"), labels, series)
        return writeArtifact(ctx, args.outputName(".svg"), ".svg", "image/svg+xml", svg.toByteArray(Charsets.UTF_8))
    }
}
