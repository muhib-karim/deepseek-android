package app.dsandroid.core.artifacts

data class FencedArtifact(val format: String, val content: String)

/** Parses complete CommonMark-style fences; unsupported outer fences still consume their body. */
object FencedArtifacts {
    fun parse(text: String): List<FencedArtifact> {
        if (text.length > 8 * 1024 * 1024) return emptyList()
        val results = mutableListOf<FencedArtifact>()
        var marker = ' '
        var markerLength = 0
        var format: String? = null
        var body = StringBuilder()
        var oversized = false
        for (line in text.lineSequence()) {
            if (markerLength == 0) {
                val opening = Regex("^ {0,3}(`{3,}|~{3,})([^`]*)$").matchEntire(line) ?: continue
                marker = opening.groupValues[1].first()
                markerLength = opening.groupValues[1].length
                val language = opening.groupValues[2].trim().substringBefore(' ').lowercase()
                format = runCatching { ArtifactFormat.parse(language) }.getOrNull()?.let {
                    when (it) { ArtifactFormat.MARKDOWN -> "markdown"; ArtifactFormat.MERMAID -> "mermaid"; else -> it.extension }
                }
                body = StringBuilder()
                oversized = false
            } else {
                val trimmed = line.dropWhile { it == ' ' }.trimEnd()
                val closes = line.takeWhile { it == ' ' }.length <= 3 && trimmed.length >= markerLength && trimmed.all { it == marker }
                if (closes) {
                    if (format != null && !oversized && body.isNotEmpty()) {
                        val content = body.toString().removeSuffix("\n")
                        if (content.toByteArray(Charsets.UTF_8).size <= ArtifactStore.MAX_BYTES) results.add(FencedArtifact(format, content))
                    }
                    markerLength = 0
                    if (results.size == 8) break
                } else if (!oversized && format != null) {
                    if (body.length + line.length + 1 > ArtifactStore.MAX_BYTES + 1) oversized = true
                    else body.append(line).append('\n')
                }
            }
        }
        return results
    }
}
