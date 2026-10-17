package app.dsandroid.core.artifacts

import app.dsandroid.core.markdown.MarkdownBlock
import app.dsandroid.core.markdown.MarkdownParser

internal fun escapeMarkup(value: String): String = value.replace("&", "&amp;").replace("<", "&lt;")
    .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")

/** Original static renderer reuses the chat parser. Raw markup and URLs never become active content. */
object MarkdownPreview {
    fun render(source: String): String = buildString {
        require(source.length <= ArtifactStore.MAX_BYTES)
        fun inline(text: String): String = MarkdownParser.inline(text).joinToString("") { span ->
            var value = escapeMarkup(span.text)
            if (span.code) value = "<code>$value</code>"
            if (span.bold) value = "<strong>$value</strong>"
            if (span.italic) value = "<em>$value</em>"
            if (span.strikethrough) value = "<s>$value</s>"
            value
        }
        MarkdownParser.parse(source).forEach { block -> append(when (block) {
            is MarkdownBlock.Heading -> "<h${block.level}>${inline(block.text)}</h${block.level}>"
            is MarkdownBlock.Paragraph -> "<p>${inline(block.text)}</p>"
            is MarkdownBlock.Quote -> "<blockquote>${inline(block.text)}</blockquote>"
            is MarkdownBlock.Code -> "<pre><code>${escapeMarkup(block.text)}</code></pre>"
            is MarkdownBlock.ListBlock -> {
                val tag = if (block.ordered) "ol" else "ul"
                "<$tag>" + block.items.joinToString("") { "<li>${inline(it)}</li>" } + "</$tag>"
            }
            is MarkdownBlock.Table -> "<table><thead><tr>" + block.header.joinToString("") { "<th>${inline(it)}</th>" } +
                "</tr></thead><tbody>" + block.rows.joinToString("") { row -> "<tr>" + row.joinToString("") { "<td>${inline(it)}</td>" } + "</tr>" } + "</tbody></table>"
            MarkdownBlock.Rule -> "<hr>"
        }) }
    }
}
