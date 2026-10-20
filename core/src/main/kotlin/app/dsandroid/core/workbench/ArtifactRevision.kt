package app.dsandroid.core.workbench

/**
 * "Ask DeepSeek to change this": the follow-up message that carries the edited source and the user's instruction,
 * and the code block taken from DeepSeek's reply as the next version.
 */
object ArtifactRevision {
    const val MAX_INSTRUCTION_CHARS = 2000
    private val htmlHint = Regex("<(!doctype\\s+html|html|head|body|div|main|section|p|h[1-6]|script|style|button|table)[\\s>/]", RegexOption.IGNORE_CASE)

    fun prompt(artifact: WorkbenchArtifact, baseVersion: Int, source: String, instruction: String): String {
        val request = checkInstruction(instruction)
        val checked = WorkbenchArtifact.checkSource(source)
        val language = artifact.kind.language
        val fence = fenceFor(checked)
        return buildString {
            append("Please update the ${artifact.kind.label} artifact \"${artifact.title}\" (version $baseVersion).\n\n")
            append("Change request: ").append(request).append("\n\n")
            append("Reply with the complete updated file in one ```$language code block (use four or more backticks if the file ")
            append("itself contains ``` lines), then at most two short sentences about what changed. Keep everything the request ")
            append("does not mention.\n\nCurrent source:\n")
            append(fence).append(language).append('\n').append(checked.trimEnd('\n', '\r')).append('\n').append(fence)
        }
    }

    /** The short text shown in the chat bubble instead of the whole prompt. */
    fun display(artifact: WorkbenchArtifact, baseVersion: Int, instruction: String): String =
        "Change “${artifact.title}” (v$baseVersion): ${checkInstruction(instruction)}"

    /** The last complete block of [kind] in the reply; an untagged block counts only when its content looks like [kind]. */
    fun extract(reply: String, kind: ArtifactKind): String? {
        val fences = CodeFences.parse(reply).filter { it.content.isNotBlank() }
        return (fences.lastOrNull { ArtifactKind.fromFence(it.language, it.content) == kind }
            ?: fences.lastOrNull { it.language.isBlank() && looksLike(kind, it.content) })?.content
    }

    /** A fence longer than any backtick run inside [source], so the source can never close it early. */
    fun fenceFor(source: String): String = "`".repeat(maxOf(3, (Regex("`+").findAll(source).maxOfOrNull { it.value.length } ?: 0) + 1))

    private fun looksLike(kind: ArtifactKind, content: String): Boolean = when (kind) {
        ArtifactKind.HTML -> htmlHint.containsMatchIn(content.take(4096)) && !ArtifactKind.looksLikeSvg(content)
        ArtifactKind.SVG -> ArtifactKind.looksLikeSvg(content)
        ArtifactKind.MARKDOWN -> false
    }

    private fun checkInstruction(instruction: String): String {
        val request = instruction.trim()
        require(request.isNotEmpty() && request.length <= MAX_INSTRUCTION_CHARS) { "Describe the change in 1 to $MAX_INSTRUCTION_CHARS characters" }
        return request
    }
}
