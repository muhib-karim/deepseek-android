package app.dsandroid.core.export

import app.dsandroid.core.agent.TurnStep
import app.dsandroid.core.artifacts.MarkdownPreview
import app.dsandroid.core.persistence.ConversationRecord

/** UI supplies translated labels; internal reasoning, prompts and tool arguments are excluded. */
data class ExportLabels(val user: String, val assistant: String, val sources: String)
enum class ExportFormat(val extension: String, val mime: String) {
    MARKDOWN("md", "text/markdown"), HTML("html", "text/html"), PDF("pdf", "application/pdf")
}
object ConversationExporter {
    const val MAX_CHARS = 100_000
    fun markdown(record: ConversationRecord, labels: ExportLabels, answerId: String? = null): String {
        val items = if (answerId == null) record.items else listOf(requireNotNull(record.items.find { it.id == answerId && it.role == "assistant" }))
        return buildString {
            append("# ${record.title.replace('\n', ' ')}\n\n")
            items.forEach { item ->
                if (answerId == null) append("## ${if (item.role == "user") labels.user else labels.assistant}\n\n")
                if (item.role == "user") append(item.text)
                else append(item.steps.filterIsInstance<TurnStep.Answer>().joinToString("\n\n") { it.text })
                append("\n\n")
                if (item.sources.isNotEmpty()) {
                    append("### ${labels.sources}\n\n")
                    item.sources.forEach { append("[${it.number}] ${it.title.replace('\n', ' ')} — ${it.url}\n\n") }
                }
                require(length <= MAX_CHARS) { "Export exceeds text limit" }
            }
        }
    }
    fun html(markdown: String, language: String): String {
        require(markdown.length <= MAX_CHARS)
        val lang = if (language.startsWith("bn")) "bn" else "en"
        return """<!doctype html><html lang="$lang"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1"><meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'; base-uri 'none'; form-action 'none'"><style>body{font-family:system-ui,sans-serif;max-width:52rem;margin:auto;padding:2rem;line-height:1.6;overflow-wrap:anywhere}pre{white-space:pre-wrap}table{border-collapse:collapse}td,th{border:1px solid #aaa;padding:.5rem}</style></head><body>${MarkdownPreview.render(markdown)}</body></html>"""
    }
}
