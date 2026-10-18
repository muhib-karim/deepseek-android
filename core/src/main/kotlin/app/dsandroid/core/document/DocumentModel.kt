package app.dsandroid.core.document

import app.dsandroid.core.markdown.InlineSpan
import app.dsandroid.core.markdown.MarkdownBlock
import app.dsandroid.core.markdown.MarkdownParser
import app.dsandroid.core.markdown.TableAlignment

/** Format-neutral blocks shared by the PDF layout engine and the DOCX writer. */
sealed interface DocBlock {
    data class Heading(val level: Int, val spans: List<InlineSpan>) : DocBlock
    data class Paragraph(val spans: List<InlineSpan>) : DocBlock
    /** [number] is the visible ordinal for ordered items; [depth] starts at 0. */
    data class ListItem(val ordered: Boolean, val number: Int, val depth: Int, val spans: List<InlineSpan>) : DocBlock
    data class Code(val language: String, val text: String) : DocBlock
    data class Quote(val spans: List<InlineSpan>) : DocBlock
    data class Table(val header: List<List<InlineSpan>>, val rows: List<List<List<InlineSpan>>>, val alignments: List<TableAlignment>) : DocBlock
    data object Rule : DocBlock
    data object PageBreak : DocBlock
}

object DocumentModel {
    const val MAX_MARKDOWN_CHARS = 400_000
    private val pageBreak = Regex(
        """^\s*(\\pagebreak|\\newpage|<!--\s*page\s*-?\s*break\s*-->|<div\s+style\s*=\s*["']\s*page-break-(after|before)\s*:\s*always;?\s*["']\s*>\s*</div>)\s*$""",
        RegexOption.IGNORE_CASE)
    private val fence = Regex("^ {0,3}(`{3,}|~{3,})")

    fun parse(markdown: String): List<DocBlock> {
        require(markdown.length <= MAX_MARKDOWN_CHARS) { "Document text exceeds $MAX_MARKDOWN_CHARS characters" }
        val lines = markdown.replace("\r\n", "\n").replace('\r', '\n').replace("\t", "    ").split('\n')
        val result = mutableListOf<DocBlock>()
        val segment = mutableListOf<String>()
        var openFence: String? = null
        fun flush() {
            if (segment.isNotEmpty()) result += convert(MarkdownParser.parse(segment.joinToString("\n")))
            segment.clear()
        }
        for (line in lines) {
            val marker = fence.find(line)?.groupValues?.get(1)
            when {
                openFence != null -> {
                    val trimmed = line.trim()
                    if (trimmed.length >= openFence!!.length && trimmed.all { it == openFence!![0] }) openFence = null
                    segment += line
                }
                marker != null -> { openFence = marker; segment += line }
                pageBreak.matches(line) -> { flush(); result += DocBlock.PageBreak }
                else -> segment += line
            }
        }
        flush()
        return result
    }

    private fun convert(blocks: List<MarkdownBlock>): List<DocBlock> {
        val result = mutableListOf<DocBlock>()
        // Indentation stack and per-depth counters persist across adjacent lists, so a numbered list
        // with bulleted children (parsed as separate blocks) keeps its nesting and numbering.
        val indents = mutableListOf<Int>()
        val counters = mutableListOf<Int>()
        val orderedAt = mutableListOf<Boolean>()
        for (block in blocks) {
            if (block !is MarkdownBlock.ListBlock) { indents.clear(); counters.clear(); orderedAt.clear() }
            when (block) {
                is MarkdownBlock.Heading -> result += DocBlock.Heading(block.level.coerceIn(1, 6), MarkdownParser.inline(block.text))
                is MarkdownBlock.Paragraph -> result += DocBlock.Paragraph(MarkdownParser.inline(block.text))
                is MarkdownBlock.ListBlock -> block.items.forEachIndexed { index, item ->
                    val width = block.indents.getOrElse(index) { 0 }
                    while (indents.isNotEmpty() && width < indents.last()) indents.removeAt(indents.lastIndex)
                    if (indents.isEmpty() || width > indents.last()) indents += width
                    val depth = (indents.size - 1).coerceIn(0, 5)
                    while (counters.size > depth + 1) { counters.removeAt(counters.lastIndex); orderedAt.removeAt(orderedAt.lastIndex) }
                    while (counters.size < depth + 1) { counters += 0; orderedAt += block.ordered }
                    if (orderedAt[depth] != block.ordered) { counters[depth] = 0; orderedAt[depth] = block.ordered }
                    counters[depth] = if (counters[depth] == 0 && index == 0 && block.ordered) block.start else counters[depth] + 1
                    result += DocBlock.ListItem(block.ordered, counters[depth], depth, MarkdownParser.inline(item))
                }
                is MarkdownBlock.Quote -> result += DocBlock.Quote(MarkdownParser.inline(block.text))
                is MarkdownBlock.Code -> result += DocBlock.Code(block.language, block.text)
                is MarkdownBlock.Table -> result += DocBlock.Table(block.header.map(MarkdownParser::inline),
                    block.rows.map { row -> row.map(MarkdownParser::inline) }, block.alignments)
                MarkdownBlock.Rule -> result += DocBlock.Rule
            }
        }
        return result
    }

    /** Plain text of inline spans, for measuring and accessibility fallbacks. */
    fun plain(spans: List<InlineSpan>): String = spans.joinToString("") { it.text }
}
