package app.dsandroid.core.document

import app.dsandroid.core.tools.Csv

/** RFC 4180 output: CRLF rows, quotes only where needed, cells kept as literal strings. */
object CsvWriter {
    const val MAX_CHARS = 4_000_000

    /** Validates model-written CSV text and normalizes line endings and quoting. */
    fun normalize(text: String): String {
        require(text.length <= MAX_CHARS) { "CSV exceeds $MAX_CHARS characters" }
        require(text.count { it == '"' } % 2 == 0) { "CSV has an unclosed quoted field" }
        val rows = Csv.decode(text.removePrefix("\uFEFF"))
        require(rows.isNotEmpty() && rows.any { row -> row.any { it.isNotEmpty() } }) { "CSV is empty" }
        return fromRows(rows)
    }

    fun fromRows(rows: List<List<String>>): String {
        require(rows.size <= 200_000) { "CSV has too many rows" }
        return Csv.encode(rows)
    }
}
