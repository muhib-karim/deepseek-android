package app.dsandroid.core.tools

import kotlinx.serialization.json.*

/** Shared structured input for the PDF tool; plain content remains compatible with chat export. */
object PdfContent {
    data class Row(val cells: List<String>, val heading: Boolean = false, val table: Boolean = false)
    val schema = objectSchema(mapOf(
        "fileName" to stringSchema("New .pdf file name"),
        "content" to stringSchema("Plain text, for conversation exports; use this OR title and blocks"),
        "title" to stringSchema("Document title"),
        "blocks" to arraySchema(objectSchema(mapOf(
            "heading" to stringSchema("Section heading"),
            "paragraph" to stringSchema("Paragraph"),
            "table" to arraySchema(arraySchema(stringSchema("Cell text"))),
        ), emptyList())),
    ), listOf("fileName"))

    fun rows(args: JsonObject): List<Row> {
        val rows = mutableListOf<Row>()
        var characters = 0L
        fun append(row: Row) {
            characters += row.cells.sumOf { it.length.toLong() }
            require(characters <= 100_000 && rows.size < 2000) { "PDF content exceeds limit" }
            rows += row
        }
        if ("content" in args) {
            require("title" !in args && "blocks" !in args)
            append(Row(listOf(args.stringArg("content"))))
        } else {
            val blocks = args.arrayArg("blocks")
            require(blocks.size <= 1000)
            append(Row(listOf(args.stringArg("title")), heading = true))
            blocks.forEach { value ->
                val block = value.jsonObject
                require(block.size == 1)
                when (block.keys.single()) {
                    "heading" -> append(Row(listOf(block.stringArg("heading")), heading = true))
                    "paragraph" -> append(Row(listOf(block.stringArg("paragraph"))))
                    "table" -> {
                        val table = block.arrayArg("table")
                        require(table.size in 1..500 && rows.size + table.size <= 2000)
                        val width = table.first().jsonArray.size
                        require(width in 1..8)
                        table.forEach { row ->
                            require(row.jsonArray.size == width)
                            append(Row(row.jsonArray.map { it.textValue() }, table = true))
                        }
                    }
                    else -> throw IllegalArgumentException("Unsupported PDF block")
                }
            }
        }
        return rows
    }
}
