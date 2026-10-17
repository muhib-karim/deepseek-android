package app.dsandroid.core.artifacts

import app.dsandroid.core.tools.Csv

/** Static table output, with explicit limits rather than an incomplete preview. */
object CsvPreview {
    fun render(source: String): String {
        require(source.length <= ArtifactStore.MAX_BYTES)
        val rows = Csv.decode(source)
        require(rows.size <= 500 && rows.all { it.size <= 50 }) { "Table exceeds preview limits" }
        return buildString {
            append("<table>")
            rows.forEachIndexed { index, row ->
                val tag = if (index == 0) "th" else "td"
                append("<tr>")
                row.forEach { append("<$tag>${escapeMarkup(it)}</$tag>") }
                append("</tr>")
            }
            append("</table>")
        }
    }
}
