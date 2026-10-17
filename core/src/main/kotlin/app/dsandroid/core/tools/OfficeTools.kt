package app.dsandroid.core.tools

import kotlinx.serialization.json.*

internal fun arraySchema(items: JsonObject): JsonObject = buildJsonObject { put("type","array"); put("items",items) }
internal fun JsonObject.arrayArg(key: String): JsonArray = this[key] as? JsonArray ?: throw IllegalArgumentException("Missing $key")
internal fun JsonElement.textValue(): String { require(this is JsonPrimitive && isString) { "Expected text" }; return content }
internal fun JsonObject.outputName(extension: String): String = if (containsKey("fileName")) stringArg("fileName") else "artifact-${java.util.UUID.randomUUID()}$extension"
private val stringArray = arraySchema(stringSchema("Text"))
private val rowsSchema = arraySchema(stringArray)
private val spreadsheetRowsSchema = arraySchema(arraySchema(buildJsonObject {
    put("type", JsonArray(listOf(JsonPrimitive("string"), JsonPrimitive("number"))))
}))
class CreateDocxTool : Tool {
    override val name = "create_docx"; override val description = "Create Word headings, paragraphs, bullet lists and tables."
    override val kind = ToolKind.WRITE; override val risk = Risk.SAFE
    override val schema = objectSchema(mapOf("fileName" to stringSchema("Optional .docx name"),"title" to stringSchema("Title"),"blocks" to arraySchema(objectSchema(mapOf("heading" to stringSchema("Heading"),"paragraph" to stringSchema("Paragraph"),"bullets" to stringArray,"table" to rowsSchema),emptyList()))),listOf("title","blocks"))
    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        val blocks = args.arrayArg("blocks").map { value -> val b = value.jsonObject; DocxBlock(b["heading"]?.textValue(),b["paragraph"]?.textValue(),b["bullets"]?.jsonArray?.map { it.textValue() },b["table"]?.jsonArray?.map { row -> row.jsonArray.map { it.textValue() } }) }
        return writeArtifact(ctx,args.outputName(".docx"),".docx","application/vnd.openxmlformats-officedocument.wordprocessingml.document",DocxBuilder.build(args.stringArg("title"),blocks))
    }
}
class CreateXlsxTool : Tool {
    override val name = "create_xlsx"; override val description = "Create Excel sheets with inline text and numeric cells."
    override val kind = ToolKind.WRITE; override val risk = Risk.SAFE
    override val schema = objectSchema(mapOf("fileName" to stringSchema("Optional .xlsx name"), "sheets" to arraySchema(objectSchema(mapOf("name" to stringSchema("Sheet name"), "rows" to spreadsheetRowsSchema)))), listOf("sheets"))
    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        val sheets = args.arrayArg("sheets").map { val s=it.jsonObject; Sheet(s.stringArg("name"),s.arrayArg("rows").map { row -> row.jsonArray.map { cell -> require(cell is JsonPrimitive && cell !is JsonNull); cell.content } }) }
        return writeArtifact(ctx,args.outputName(".xlsx"),".xlsx","application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",XlsxBuilder.build(sheets))
    }
}
class CreatePptxTool : Tool {
    override val name = "create_pptx"; override val description = "Create a wide PowerPoint title slide and content slides with optional speaker notes."
    override val kind = ToolKind.WRITE; override val risk = Risk.SAFE
    override val schema = objectSchema(mapOf("fileName" to stringSchema("Optional .pptx name"),"title" to stringSchema("Deck title"),"slides" to arraySchema(objectSchema(mapOf("title" to stringSchema("Slide title"),"bullets" to stringArray,"notes" to stringSchema("Speaker notes")),listOf("title","bullets")))),listOf("title","slides"))
    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        val slides = args.arrayArg("slides").map { val s=it.jsonObject; Slide(s.stringArg("title"),s.arrayArg("bullets").map { it.textValue() },s["notes"]?.textValue()) }
        return writeArtifact(ctx,args.outputName(".pptx"),".pptx","application/vnd.openxmlformats-officedocument.presentationml.presentation",PptxBuilder.build(args.stringArg("title"),slides))
    }
}
