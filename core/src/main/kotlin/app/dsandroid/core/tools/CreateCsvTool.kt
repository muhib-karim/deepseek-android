package app.dsandroid.core.tools

import kotlinx.serialization.json.*

object Csv {
    fun encode(rows: List<List<String>>): String = rows.joinToString("") { row ->
        row.joinToString(",") { cell ->
            if (cell.any { it == ',' || it == '"' || it == '\r' || it == '\n' }) "\"${cell.replace("\"", "\"\"")}\"" else cell
        } + "\r\n"
    }
}

class CreateCsvTool : Tool {
    override val name = "create_csv"
    override val description = "Create a new RFC 4180 CSV artifact. Cells are literal strings."
    override val risk = Risk.SAFE
    override val kind = ToolKind.WRITE
    override val schema = objectSchema(mapOf("fileName" to stringSchema("A new file ending in .csv"), "rows" to buildJsonObject {
        put("type", "array")
        put("items", buildJsonObject { put("type", "array"); put("items", stringSchema("Cell text")) })
    }))
    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        val rows = (args["rows"] as? JsonArray ?: throw IllegalArgumentException("Missing rows")).map { row ->
            (row as? JsonArray ?: throw IllegalArgumentException("Invalid row")).map { cell ->
                require(cell is JsonPrimitive && cell.isString) { "Cells must be strings" }
                cell.content
            }
        }
        return writeArtifact(ctx, args.stringArg("fileName"), ".csv", "text/csv", Csv.encode(rows).toByteArray(Charsets.UTF_8))
    }
}
