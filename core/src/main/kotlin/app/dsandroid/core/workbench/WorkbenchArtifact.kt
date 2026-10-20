package app.dsandroid.core.workbench

import java.security.MessageDigest
import kotlinx.serialization.Serializable

/** The three formats the artifacts panel previews: HTML in a sandboxed WebView, SVG with AndroidSVG, Markdown natively. */
enum class ArtifactKind(val language: String, val extension: String, val mimeType: String, val label: String) {
    HTML("html", "html", "text/html", "HTML"),
    SVG("svg", "svg", "image/svg+xml", "SVG"),
    MARKDOWN("markdown", "md", "text/markdown", "Markdown");

    companion object {
        private val svgStart = Regex("^\\s*(<\\?xml[^>]*>\\s*)?(<!--.*?-->\\s*)*(<!doctype[^>]*>\\s*)?<svg[\\s/>]",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))

        /** A fence's info string (first word) and body; `xml` counts only when the body is an SVG document. */
        fun fromFence(language: String, source: String): ArtifactKind? = when (language.trim().substringBefore(' ').lowercase()) {
            "html", "htm", "xhtml" -> HTML
            "svg" -> SVG
            "xml" -> SVG.takeIf { looksLikeSvg(source) }
            "markdown", "md" -> MARKDOWN
            else -> null
        }

        fun fromFileName(name: String): ArtifactKind? = when (name.substringAfterLast('/').substringAfterLast('.', "").lowercase()) {
            "html", "htm" -> HTML
            "svg" -> SVG
            "md", "markdown" -> MARKDOWN
            else -> null
        }

        fun looksLikeSvg(source: String): Boolean = svgStart.containsMatchIn(source.removePrefix("﻿").take(4096))
    }
}

/** Where a version came from; shown in the version picker. */
enum class VersionOrigin(val label: String) {
    ANSWER("From the answer"), AGENT_FILE("Agent file"), EDIT("Your edit"), DEEPSEEK("DeepSeek revision")
}

@Serializable
data class ArtifactVersion(val number: Int, val source: String, val origin: VersionOrigin, val createdAt: Long, val note: String = "")

/**
 * One artifact and its history (v1, v2, ...). Versions only ever append; past [MAX_VERSIONS] or [MAX_HISTORY_BYTES]
 * the original and the newest ones are kept, and numbers are never reused.
 */
@Serializable
data class WorkbenchArtifact(
    val id: String,
    val chatId: String,
    val title: String,
    val kind: ArtifactKind,
    val versions: List<ArtifactVersion>,
    val filePath: String? = null,
) {
    init {
        require(id.matches(ID)) { "Invalid artifact id" }
        require(chatId.isNotBlank() && chatId.length <= 200) { "Invalid chat id" }
        require(title.isNotBlank() && title.length <= MAX_TITLE) { "Invalid artifact title" }
        require(versions.size in 1..MAX_VERSIONS) { "Invalid artifact history" }
        require(versions.zipWithNext().all { (a, b) -> a.number < b.number } && versions.first().number >= 1) { "Invalid version order" }
    }

    val latest: ArtifactVersion get() = versions.last()

    fun version(number: Int): ArtifactVersion? = versions.firstOrNull { it.number == number }

    /** Appends the next version, or returns this artifact unchanged when [source] equals the latest version. */
    fun withVersion(source: String, origin: VersionOrigin, now: Long, note: String = ""): WorkbenchArtifact {
        val checked = checkSource(source)
        if (checked == latest.source) return this
        val kept = (versions + ArtifactVersion(latest.number + 1, checked, origin, now, note.trim().take(MAX_NOTE))).toMutableList()
        fun bytes() = kept.sumOf { it.source.length.toLong() * 3 }
        // Drop the oldest edits (never v1 or the new version) past the count or the size budget.
        while (kept.size > 2 && (kept.size > MAX_VERSIONS || bytes() > MAX_HISTORY_BYTES)) kept.removeAt(1)
        return copy(versions = kept)
    }

    /** Lines added and removed by version [number] against the kept version before it; null for the first. */
    fun changeFromPrevious(number: Int): DiffStats? {
        val index = versions.indexOfFirst { it.number == number }
        if (index <= 0) return null
        return LineDiff.stats(versions[index - 1].source, versions[index].source)
    }

    companion object {
        const val MAX_VERSIONS = 30
        const val MAX_SOURCE_BYTES = 512 * 1024
        /** Upper bound on the UTF-8 size of a whole history (3 bytes per char at most), so one file stays small. */
        const val MAX_HISTORY_BYTES = 6L * 1024 * 1024
        const val MAX_TITLE = 80
        private const val MAX_NOTE = 300
        private val ID = Regex("[a-z0-9]{8,64}")

        fun create(id: String, chatId: String, title: String, kind: ArtifactKind, source: String, origin: VersionOrigin, now: Long,
            filePath: String? = null): WorkbenchArtifact {
            val checked = checkSource(source)
            val name = title.trim().ifBlank { ArtifactTitles.guess(kind, checked) }.take(MAX_TITLE)
            return WorkbenchArtifact(id, chatId, name, kind, listOf(ArtifactVersion(1, checked, origin, now)), filePath)
        }

        fun checkSource(source: String): String {
            require(source.isNotBlank()) { "The artifact is empty" }
            require(source.toByteArray(Charsets.UTF_8).size <= MAX_SOURCE_BYTES) { "The artifact exceeds 512 KB" }
            return source
        }
    }
}

/** Stable ids: the same fence in the same message, or the same workspace file, always reopens the same artifact. */
object WorkbenchIds {
    fun forFence(chatId: String, messageId: String, language: String, source: String): String =
        "f" + digest("fence\u0000$chatId\u0000$messageId\u0000${language.trim().lowercase()}\u0000$source").take(40)

    fun forFile(chatId: String, path: String): String = "p" + digest("file\u0000$chatId\u0000$path").take(40)

    private fun digest(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}

object ArtifactTitles {
    private val htmlTitle = Regex("<title[^>]*>(.*?)</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val htmlHeading = Regex("<h1[^>]*>(.*?)</h1>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val svgTitle = Regex("<title[^>]*>(.*?)</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val markdownHeading = Regex("^ {0,3}#{1,6}\\s+(.+?)\\s*#*\\s*$", RegexOption.MULTILINE)

    fun guess(kind: ArtifactKind, source: String): String {
        val head = source.take(64 * 1024)
        val found = when (kind) {
            ArtifactKind.HTML -> (htmlTitle.find(head) ?: htmlHeading.find(head))?.groupValues?.get(1)?.replace(Regex("<[^>]*>"), "")
            ArtifactKind.SVG -> svgTitle.find(head)?.groupValues?.get(1)
            ArtifactKind.MARKDOWN -> markdownHeading.find(head)?.groupValues?.get(1)?.replace(Regex("[*_`]"), "")
        }
        val clean = found?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
        val fallback = when (kind) { ArtifactKind.HTML -> "HTML page"; ArtifactKind.SVG -> "SVG image"; ArtifactKind.MARKDOWN -> "Markdown document" }
        return clean.ifEmpty { fallback }.take(WorkbenchArtifact.MAX_TITLE)
    }
}

data class CodeFence(val language: String, val content: String)

/** Complete CommonMark-style fences (backticks or tildes, closed by an equal or longer run); unclosed fences are ignored. */
object CodeFences {
    private val opening = Regex("^ {0,3}(`{3,}|~{3,})(.*)$")

    fun parse(text: String, limit: Int = 64): List<CodeFence> {
        val results = mutableListOf<CodeFence>()
        var marker = ' '
        var length = 0
        var language = ""
        val body = StringBuilder()
        for (line in text.lineSequence()) {
            if (length == 0) {
                val match = opening.matchEntire(line.trimEnd('\r')) ?: continue
                val run = match.groupValues[1]
                val info = match.groupValues[2]
                if (run.first() == '`' && info.contains('`')) continue
                marker = run.first(); length = run.length
                language = info.trim().substringBefore(' ')
                body.setLength(0)
            } else {
                val clean = line.trimEnd('\r')
                val indent = clean.takeWhile { it == ' ' }.length
                val rest = clean.trim()
                if (indent <= 3 && rest.length >= length && rest.all { it == marker }) {
                    results += CodeFence(language, body.toString().removeSuffix("\n"))
                    length = 0
                    if (results.size >= limit) break
                } else body.append(clean).append('\n')
            }
        }
        return results
    }
}
