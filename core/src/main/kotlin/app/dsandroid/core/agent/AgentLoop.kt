package app.dsandroid.core.agent

import app.dsandroid.core.guard.Decision
import app.dsandroid.core.guard.GuardPolicy
import app.dsandroid.core.guard.GuardSettings
import app.dsandroid.core.llm.*
import app.dsandroid.core.tools.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlin.coroutines.coroutineContext

fun interface Confirmer {
    suspend fun confirm(toolName: String, arguments: JsonObject): Boolean
}

sealed interface AgentEvent {
    data class TextDelta(val text: String, val turn: Int) : AgentEvent
    data class ReasoningDelta(val text: String, val turn: Int) : AgentEvent
    data class ToolStarted(val callId: String, val name: String) : AgentEvent
    data class ToolNeedsConfirm(val callId: String, val name: String, val arguments: JsonObject) : AgentEvent
    data class ToolFinished(val callId: String, val name: String, val result: ToolResult) : AgentEvent
    data class ArtifactCreated(val artifact: Artifact) : AgentEvent
    data class Error(val message: String) : AgentEvent
    data object Done : AgentEvent
}

class AgentLoop(
    private val client: LlmClient,
    private val registry: ToolRegistry,
    private val guard: GuardPolicy,
    private val context: ToolContext,
    private val confirmer: Confirmer,
    private val settings: GuardSettings = GuardSettings(),
    private val maxSteps: Int = 8,
) {
    init { require(maxSteps in 1..8) }

    /** Caller owns history and must not mutate it while a run is active. */
    fun run(history: MutableList<ChatMessage>): Flow<AgentEvent> = flow {
        repeat(maxSteps) { step ->
            coroutineContext.ensureActive()
            val accumulator = StreamAccumulator()
            client.stream(history.toList(), registry.specs()).collect { chunk ->
                accumulator.accept(chunk)
                chunk.choices.filter { it.index == 0 }.forEach { choice ->
                    choice.delta.content?.let { emit(AgentEvent.TextDelta(it, step)) }
                    choice.delta.reasoningContent?.let { emit(AgentEvent.ReasoningDelta(it, step)) }
                }
            }
            val assistant = accumulator.message()
            val calls = assistant.toolCalls.orEmpty()
            if (calls.isEmpty()) {
                history.add(assistant)
                emit(AgentEvent.Done)
                return@flow
            }
            // Commit an assistant/tool group together so cancellation never leaves orphan calls.
            val completedTurn = mutableListOf(assistant)
            for (call in calls) {
                coroutineContext.ensureActive()
                val args = runCatching { LlmJson.parseToJsonElement(call.function.arguments) as? JsonObject }.getOrNull()
                val result = if (args == null) ToolResult("Invalid tool arguments: expected a JSON object", isError = true)
                else {
                    val decision = guard.decide(call.function.name, args, settings)
                    val allowed = when (decision) {
                        Decision.ALLOW -> true
                        Decision.DENY -> false
                        Decision.CONFIRM -> {
                            emit(AgentEvent.ToolNeedsConfirm(call.id, call.function.name, args))
                            confirmer.confirm(call.function.name, args)
                        }
                    }
                    if (!allowed) ToolResult(if (decision == Decision.DENY) "Denied by safety policy or unknown tool" else "User declined this tool call", isError = true)
                    else {
                        coroutineContext.ensureActive()
                        emit(AgentEvent.ToolStarted(call.id, call.function.name))
                        try {
                            withContext(Dispatchers.IO) { registry[call.function.name]!!.run(args, context) }
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { ToolResult("Tool failed: check the arguments, file name, size limit and workspace restrictions", isError = true) }
                    }
                }
                completedTurn.add(ChatMessage("tool", LlmJson.encodeToString(result), toolCallId = call.id))
                emit(AgentEvent.ToolFinished(call.id, call.function.name, result))
                result.artifacts.forEach { emit(AgentEvent.ArtifactCreated(it)) }
            }
            history.addAll(completedTurn)
        }
        emit(AgentEvent.Error("Stopped after $maxSteps assistant steps. Send a new message to continue."))
    }.catch { error ->
        if (error is CancellationException) throw error
        emit(AgentEvent.Error("The assistant request failed or its stream was incomplete. Check provider settings and connection."))
    }
}
