package app.dsandroid.core.markdown

enum class TableAlignment { LEFT, CENTER, RIGHT }

sealed interface MarkdownBlock {
    data class Heading(val level: Int, val text: String) : MarkdownBlock
    data class Paragraph(val text: String) : MarkdownBlock
    data class ListBlock(val ordered: Boolean, val items: List<String>, val start: Int = 1) : MarkdownBlock
    data class Quote(val text: String) : MarkdownBlock
    data class Code(val text: String, val language: String) : MarkdownBlock
    data class Table(val header: List<String>, val alignments: List<TableAlignment>, val rows: List<List<String>>) : MarkdownBlock
    data object Rule : MarkdownBlock
}

data class InlineSpan(
    val text: String, val bold: Boolean = false, val italic: Boolean = false,
    val code: Boolean = false, val link: String? = null, val strikethrough: Boolean = false,
)

/** A small, streaming-tolerant Markdown subset. Raw HTML is text, never executable. */
object MarkdownParser {
    private val heading = Regex("^ {0,3}(#{1,6})\\s+(.*)$")
    private val bullet = Regex("^\\s*[-+*]\\s+(.*)$")
    private val numbered = Regex("^\\s*(\\d+)[.)]\\s+(.*)$")
    private val fence = Regex("^ {0,3}(`{3,}|~{3,})(.*)$")
    private val rule = Regex("^ {0,3}((\\*\\s*){3,}|(-\\s*){3,}|(_\\s*){3,})$")
    private val alignment = Regex(":?-{3,}:?")

    fun parse(text: String): List<MarkdownBlock> {
        val lines = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val blocks = mutableListOf<MarkdownBlock>()
        var index = 0
        fun isTable(at: Int): Boolean {
            if (at + 1 >= lines.size || '|' !in lines[at]) return false
            val cells = tableCells(lines[at + 1])
            return cells.isNotEmpty() && cells.all { alignment.matches(it) } && cells.size == tableCells(lines[at]).size
        }
        fun special(at: Int): Boolean = lines[at].isBlank() || heading.matches(lines[at]) || fence.matches(lines[at]) ||
            rule.matches(lines[at]) || bullet.matches(lines[at]) || numbered.matches(lines[at]) || lines[at].trimStart().startsWith('>') || isTable(at)
        while (index < lines.size) {
            val line = lines[index]
            val code = fence.matchEntire(line)
            val title = heading.matchEntire(line)
            when {
                line.isBlank() -> index++
                code != null -> {
                    val marker = code.groupValues[1]
                    val language = code.groupValues[2].trim().substringBefore(' ')
                    val content = mutableListOf<String>()
                    index++
                    while (index < lines.size) {
                        val end = lines[index].trim()
                        if (end.length >= marker.length && end.all { it == marker[0] }) { index++; break }
                        content.add(lines[index++])
                    }
                    blocks.add(MarkdownBlock.Code(content.joinToString("\n"), language))
                }
                title != null -> { blocks.add(MarkdownBlock.Heading(title.groupValues[1].length, title.groupValues[2].replace(Regex("\\s+#+\\s*$"), ""))); index++ }
                rule.matches(line) -> { blocks.add(MarkdownBlock.Rule); index++ }
                isTable(index) -> {
                    val header = tableCells(line)
                    val alignments = tableCells(lines[index + 1]).map {
                        when { it.startsWith(':') && it.endsWith(':') -> TableAlignment.CENTER; it.endsWith(':') -> TableAlignment.RIGHT; else -> TableAlignment.LEFT }
                    }
                    index += 2
                    val rows = mutableListOf<List<String>>()
                    while (index < lines.size && lines[index].isNotBlank() && '|' in lines[index]) {
                        val cells = tableCells(lines[index++])
                        rows.add(List(header.size) { cells.getOrElse(it) { "" } })
                    }
                    blocks.add(MarkdownBlock.Table(header, alignments, rows))
                }
                bullet.matches(line) || numbered.matches(line) -> {
                    val ordered = numbered.matches(line)
                    val expression = if (ordered) numbered else bullet
                    val start = if (ordered) numbered.matchEntire(line)!!.groupValues[1].toIntOrNull() ?: 1 else 1
                    val items = mutableListOf<String>()
                    while (index < lines.size) {
                        val match = expression.matchEntire(lines[index]) ?: break
                        items.add(match.groupValues[if (ordered) 2 else 1]); index++
                    }
                    blocks.add(MarkdownBlock.ListBlock(ordered, items, start))
                }
                line.trimStart().startsWith('>') -> {
                    val content = mutableListOf<String>()
                    while (index < lines.size && lines[index].trimStart().startsWith('>')) content.add(lines[index++].trimStart().removePrefix(">").removePrefix(" "))
                    blocks.add(MarkdownBlock.Quote(content.joinToString("\n")))
                }
                else -> {
                    val content = mutableListOf(lines[index++])
                    while (index < lines.size && !special(index)) content.add(lines[index++])
                    blocks.add(MarkdownBlock.Paragraph(content.joinToString("\n")))
                }
            }
        }
        return blocks
    }

    private fun tableCells(line: String): List<String> {
        val value = line.trim().removePrefix("|").removeSuffix("|")
        val cells = mutableListOf<String>()
        val cell = StringBuilder()
        var escaped = false
        var code = false
        value.forEach { char ->
            when {
                escaped -> { cell.append(char); escaped = false }
                char == '\\' -> escaped = true
                char == '`' -> { code = !code; cell.append(char) }
                char == '|' && !code -> { cells.add(cell.toString().trim()); cell.clear() }
                else -> cell.append(char)
            }
        }
        if (escaped) cell.append('\\')
        cells.add(cell.toString().trim())
        return cells
    }

    fun inline(text: String): List<InlineSpan> = inline(text, InlineSpan(""), 0)

    private fun inline(text: String, style: InlineSpan, depth: Int): List<InlineSpan> {
        if (depth > 16) return listOf(style.copy(text = text.replace("*", "").replace("`", "")))
        val result = mutableListOf<InlineSpan>()
        val plain = StringBuilder()
        fun flush() { if (plain.isNotEmpty()) { result.add(style.copy(text = plain.toString())); plain.clear() } }
        var index = 0
        while (index < text.length) {
            val char = text[index]
            if (char == '\\' && index + 1 < text.length) { plain.append(text[index + 1]); index += 2; continue }
            if (char == '[' || (char == '!' && index + 1 < text.length && text[index + 1] == '[')) {
                val start = if (char == '!') index + 2 else index + 1
                val labelEnd = text.indexOf(']', start)
                val urlEnd = if (labelEnd >= 0 && text.getOrNull(labelEnd + 1) == '(') text.indexOf(')', labelEnd + 2) else -1
                if (urlEnd >= 0) {
                    flush()
                    val url = text.substring(labelEnd + 2, urlEnd).substringBefore(' ').trim('<', '>')
                    val safeLink = url.takeIf { it.startsWith("https://") || it.startsWith("http://") }
                    result.addAll(inline(text.substring(start, labelEnd), style.copy(link = safeLink), depth + 1))
                    index = urlEnd + 1; continue
                }
            }
            val marker = when {
                text.startsWith("**", index) -> "**"
                text.startsWith("__", index) -> "__"
                text.startsWith("~~", index) -> "~~"
                char == '`' -> text.substring(index).takeWhile { it == '`' }
                char == '*' -> "*"
                char == '_' && (index == 0 || !text[index - 1].isLetterOrDigit()) -> "_"
                char == '$' -> "$"
                else -> null
            }
            if (marker == null) { plain.append(char); index++; continue }
            flush()
            val start = index + marker.length
            val end = text.indexOf(marker, start).let { if (it < 0) text.length else it }
            val content = text.substring(start, end)
            val nextStyle = when (marker) {
                "**", "__" -> style.copy(bold = true)
                "*", "_" -> style.copy(italic = true)
                "~~" -> style.copy(strikethrough = true)
                else -> style.copy(code = true)
            }
            if (nextStyle.code) { if (content.isNotEmpty()) result.add(nextStyle.copy(text = content)) }
            else result.addAll(inline(content, nextStyle, depth + 1))
            index = if (end == text.length) end else end + marker.length
        }
        flush()
        return result
    }
}
