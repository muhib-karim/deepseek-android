package app.dsandroid.core.agent

enum class ToolStatus { RUNNING, WAITING, DONE, ERROR, DENIED, STOPPED }

sealed interface TurnStep {
    data class Reasoning(val text: String, val durationMs: Long = 0, val complete: Boolean = false) : TurnStep
    data class ToolCall(
        val id: String, val name: String, val argsJson: String,
        val status: ToolStatus = ToolStatus.RUNNING, val resultPreview: String = "", val durationMs: Long? = null,
    ) : TurnStep
    data class Answer(val text: String) : TurnStep
}
