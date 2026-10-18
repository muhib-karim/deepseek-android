package app.dsandroid.core.voice

import app.dsandroid.core.markdown.MarkdownBlock
import app.dsandroid.core.markdown.MarkdownParser

data class SpeechRange(val start: Int, val end: Int)

object SpeechText {
    /** Original UTF-16 offsets, also used by Compose. Long sentences are bounded for all TTS engines. */
    fun sentences(text: String): List<SpeechRange> = buildList {
        var start = 0
        while (start < text.length) {
            while (start < text.length && text[start].isWhitespace()) start++
            if (start == text.length) break
            var end = start
            while (end < text.length && end - start < 1000) {
                val c = text[end++]
                if (c == '\n') break
                if (c in ".!?।") {
                    var boundary = end
                    while (boundary < text.length && boundary - start < 1000 && text[boundary] in "\"'’”)]}") boundary++
                    if (boundary == text.length || text[boundary].isWhitespace()) { end = boundary; break }
                }
            }
            if (end < text.length && Character.isHighSurrogate(text[end - 1]) && Character.isLowSurrogate(text[end])) end--
            var trimmed = end
            while (trimmed > start && text[trimmed - 1].isWhitespace()) trimmed--
            if (trimmed > start) add(SpeechRange(start, trimmed))
            start = end
        }
    }
    /** Reuse the chat parser so reading never exposes formatting delimiters or hidden link targets. */
    fun readableMarkdown(markdown: String): String {
        fun inline(value: String) = MarkdownParser.inline(value).joinToString("") { it.text }
        return MarkdownParser.parse(markdown).map { block ->
            when (block) {
                is MarkdownBlock.Heading -> inline(block.text)
                is MarkdownBlock.Paragraph -> inline(block.text)
                is MarkdownBlock.Quote -> inline(block.text)
                is MarkdownBlock.Code -> block.text
                is MarkdownBlock.ListBlock -> block.items.mapIndexed { index, item ->
                    (if (block.ordered) "${block.start + index}. " else "") + inline(item)
                }.joinToString("\n")
                is MarkdownBlock.Table -> (listOf(block.header) + block.rows).joinToString("\n") { row -> row.joinToString("; ") { inline(it) } }
                MarkdownBlock.Rule -> ""
            }
        }.filter { it.isNotBlank() }.joinToString("\n\n")
    }
    fun language(text: String): String = if (text.any { it in '\u0980'..'\u09ff' }) "bn" else "en"
    fun speed(value: Float): Float = if (value.isFinite()) value.coerceIn(0.5f, 2f) else 1f
}
