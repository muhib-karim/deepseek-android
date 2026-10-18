package app.dsandroid.core.chat

import kotlinx.serialization.json.*

object FollowUps {
    const val PROMPT = "\nAfter your final answer, suggest 2 or 3 short follow-up questions in the user's language. Append exactly this trailing block on a new line: <follow-ups>[\"Question one?\",\"Question two?\"]</follow-ups>. Each question must be distinct, plain text, one line and at most 160 characters. Never put the block in a code fence."
    data class Result(val answer: String, val questions: List<String> = emptyList())
    fun display(text: String, streaming: Boolean): String {
        val parsed = parse(text)
        if (parsed.questions.isNotEmpty() || !streaming) return parsed.answer
        val start = text.lastIndexOf("\n<follow-ups>")
        if (start < 0 || text.length - start > 1500) return text
        val prefix = text.substring(0, start)
        return if (prefix.lineSequence().count { it.trimStart().startsWith("```") || it.trimStart().startsWith("~~~") } % 2 == 0) prefix.trimEnd() else text
    }

    fun parse(text: String): Result {
        val start = text.lastIndexOf("\n<follow-ups>")
        if (start < 0 || !text.trimEnd().endsWith("</follow-ups>")) return Result(text)
        val prefix = text.substring(0, start)
        if (prefix.lineSequence().count { it.trimStart().startsWith("```") || it.trimStart().startsWith("~~~") } % 2 != 0) return Result(text)
        val body = text.substring(start + "\n<follow-ups>".length).trimEnd().removeSuffix("</follow-ups>")
        if (body.length > 1500) return Result(text)
        val questions = runCatching {
            Json.parseToJsonElement(body).jsonArray.map { value ->
                require(value is JsonPrimitive && value.isString)
                value.content.trim().also { q -> require(q.isNotEmpty() && q.length <= 160 && q.none { it.isISOControl() }) }
            }.also { require(it.size in 2..3 && it.distinct().size == it.size) }
        }.getOrNull() ?: return Result(text)
        return Result(prefix.trimEnd(), questions)
    }
}
