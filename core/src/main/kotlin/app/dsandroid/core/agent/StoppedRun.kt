package app.dsandroid.core.agent

import app.dsandroid.core.llm.ChatMessage
import app.dsandroid.core.llm.LlmJson
import app.dsandroid.core.tools.ToolResult
import kotlinx.serialization.encodeToString

/** Explicit Stop terminates a run; transport cancellation alone keeps it resumable. */
object StoppedRun {
    fun finish(checkpoint: RunCheckpoint, visibleSteps: List<TurnStep>): RunCheckpoint {
        val steps = (0 until maxOf(checkpoint.steps.size, visibleSteps.size)).map { index ->
            val stored = checkpoint.steps.getOrNull(index)
            val visible = visibleSteps.getOrNull(index)
            val step = if (stored is TurnStep.ToolCall && stored.status in setOf(ToolStatus.DONE, ToolStatus.ERROR, ToolStatus.DENIED)) stored
                else visible ?: requireNotNull(stored)
            when (step) {
                is TurnStep.Reasoning -> step.copy(complete = true)
                is TurnStep.ToolCall -> if (step.status in setOf(ToolStatus.RUNNING, ToolStatus.WAITING)) step.copy(status = ToolStatus.STOPPED) else step
                is TurnStep.Answer -> step
            }
        }
        val messages = checkpoint.messages.toMutableList()
        checkpoint.pendingAssistant?.toolCalls.orEmpty().drop(checkpoint.pendingToolIndex).forEach { call ->
            messages.add(ChatMessage("tool", LlmJson.encodeToString(ToolResult("Cancelled by user", isError = true)), toolCallId = call.id))
        }
        if (checkpoint.pendingAssistant == null) {
            val partial = visibleSteps.drop(checkpoint.steps.size).filterIsInstance<TurnStep.Answer>().joinToString("\n") { it.text }
            if (partial.isNotEmpty()) messages.add(ChatMessage("assistant", partial))
        }
        return checkpoint.copy(messages = messages, steps = steps, pendingAssistant = null, pendingToolIndex = 0, complete = true)
    }
}
