package app.dsandroid.ui

sealed interface MarkdownBlock {
    data class Paragraph(val text: String) : MarkdownBlock
    data class Code(val text: String, val language: String) : MarkdownBlock
}

data class InlinePart(val text: String, val bold: Boolean = false, val code: Boolean = false)

/** Small text renderer: no HTML, remote images, JavaScript or automatic links. */
object MarkdownBlocks {
    fun parse(text: String): List<MarkdownBlock> {
        val blocks = mutableListOf<MarkdownBlock>()
        val lines = mutableListOf<String>()
        var language: String? = null
        fun flush() {
            if (lines.isNotEmpty() || language != null) {
                val value = lines.joinToString("\n")
                blocks.add(language?.let { MarkdownBlock.Code(value, it) } ?: MarkdownBlock.Paragraph(value))
                lines.clear()
            }
        }
        text.replace("\r\n", "\n").split('\n').forEach { line ->
            when {
                line.startsWith("```") -> {
                    flush()
                    language = if (language == null) line.removePrefix("```").trim() else null
                }
                line.isBlank() && language == null -> flush()
                else -> lines.add(line)
            }
        }
        flush()
        return blocks
    }

    fun inline(text: String): List<InlinePart> {
        val parts = mutableListOf<InlinePart>()
        val expression = Regex("`([^`]+)`|\\*\\*([^*]+)\\*\\*")
        var end = 0
        expression.findAll(text).forEach { match ->
            if (match.range.first > end) parts.add(InlinePart(text.substring(end, match.range.first)))
            if (match.value.startsWith('`')) parts.add(InlinePart(match.groupValues[1], code = true))
            else parts.add(InlinePart(match.groupValues[2], bold = true))
            end = match.range.last + 1
        }
        if (end < text.length) parts.add(InlinePart(text.substring(end)))
        return parts
    }
}
