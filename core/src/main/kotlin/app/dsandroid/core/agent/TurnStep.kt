package app.dsandroid.core.agent
import kotlinx.serialization.Serializable

@Serializable
enum class ToolStatus { RUNNING, WAITING, DONE, ERROR, DENIED, STOPPED }

@Serializable
sealed interface TurnStep {
    @Serializable
    data class Reasoning(val text: String, val durationMs: Long = 0, val complete: Boolean = false) : TurnStep
    @Serializable
    data class ToolCall(
        val id: String, val name: String, val argsJson: String,
        val status: ToolStatus = ToolStatus.RUNNING, val resultPreview: String = "", val durationMs: Long? = null,
    ) : TurnStep
    @Serializable
    data class Answer(val text: String) : TurnStep
}
