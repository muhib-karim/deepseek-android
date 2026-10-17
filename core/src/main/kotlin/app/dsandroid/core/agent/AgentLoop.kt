package app.dsandroid.core.agent

import app.dsandroid.core.guard.Decision
import app.dsandroid.core.guard.GuardPolicy
import app.dsandroid.core.guard.GuardSettings
import app.dsandroid.core.llm.*
import app.dsandroid.core.tools.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlin.coroutines.coroutineContext

fun interface Confirmer {
    suspend fun confirm(toolName: String, arguments: JsonObject): Boolean
}

sealed interface AgentEvent {
    data class StepsUpdated(val steps: List<TurnStep>) : AgentEvent
    data class TextDelta(val text: String, val turn: Int) : AgentEvent
    data class ReasoningDelta(val text: String, val turn: Int) : AgentEvent
    data class ToolStarted(val callId: String, val name: String) : AgentEvent
    data class ToolNeedsConfirm(val callId: String, val name: String, val arguments: JsonObject) : AgentEvent
    data class ToolFinished(val callId: String, val name: String, val result: ToolResult) : AgentEvent
    data class ArtifactCreated(val artifact: Artifact) : AgentEvent
    data class Error(val message: String) : AgentEvent
    data class RetryWaiting(val remainingMs: Long, val attempt: Int, val details: String) : AgentEvent
    data class RecoverableWaiting(val checkpoint: RunCheckpoint, val message: String, val details: String) : AgentEvent
    data class AuthenticationRequired(val checkpoint: RunCheckpoint, val status: Int) : AgentEvent
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
    private val checkpointStore: CheckpointStore? = null,
    private val runConfig: RunConfig = RunConfig(),
    private val retryPolicy: RetryPolicy = RetryPolicy(),
    private val retryWait: suspend (Long) -> Unit = { delay(it) },
) {
    init { require(maxSteps in 1..8) }

    /** Caller owns history and must not mutate it while a run is active. */
    fun run(history: MutableList<ChatMessage>): Flow<AgentEvent> = execute(history, RunCheckpoint(
        runId = runConfig.runId, conversationId = runConfig.conversationId, model = runConfig.model,
        thinking = runConfig.thinking, searchEnabled = runConfig.searchEnabled, providerKey = runConfig.providerKey, messages = history.toList()))

    fun resume(checkpoint: RunCheckpoint, history: MutableList<ChatMessage> = checkpoint.messages.toMutableList()): Flow<AgentEvent> {
        require(checkpoint.providerKey == runConfig.providerKey) { "Resume requires the original provider and account" }
        history.clear(); history.addAll(checkpoint.messages)
        return execute(history, checkpoint)
    }

    private fun execute(history: MutableList<ChatMessage>, initial: RunCheckpoint): Flow<AgentEvent> = flow {
        var checkpoint = initial
        val timeline = initial.steps.toMutableList()
        suspend fun save() {
            checkpoint = checkpoint.copy(messages = history.toList(), steps = timeline.toList())
            withContext(NonCancellable) { checkpointStore?.save(checkpoint) }
        }
        if (initial.complete) { emit(AgentEvent.StepsUpdated(timeline.toList())); emit(AgentEvent.Done); return@flow }
        save()
        if (initial.steps.isNotEmpty()) emit(AgentEvent.StepsUpdated(timeline.toList()))
        var reasoningStarted = 0L
        fun finishReasoning() {
            val last = timeline.lastOrNull()
            if (last is TurnStep.Reasoning && !last.complete) timeline[timeline.lastIndex] = last.copy(
                durationMs = (System.nanoTime() - reasoningStarted) / 1_000_000, complete = true)
        }
        suspend fun publish() { emit(AgentEvent.StepsUpdated(timeline.toList())) }
        var retryElapsedMs = 0L
        for (step in initial.assistantStep until maxSteps) {
            coroutineContext.ensureActive()
            val committedTimeline = timeline.toList()
            var attempt = 0
            var assistant = checkpoint.pendingAssistant
            while (assistant == null) {
            val accumulator = StreamAccumulator()
            val requestStarted = System.nanoTime()
            try {
            client.stream(history.toList(), registry.specs()).collect { chunk ->
                accumulator.accept(chunk)
                chunk.choices.filter { it.index == 0 }.forEach { choice ->
                    choice.delta.reasoningContent?.takeIf { it.isNotEmpty() }?.let { text ->
                        val last = timeline.lastOrNull()
                        if (last is TurnStep.Reasoning && !last.complete) timeline[timeline.lastIndex] = last.copy(text = last.text + text,
                            durationMs = (System.nanoTime() - reasoningStarted) / 1_000_000)
                        else { reasoningStarted = System.nanoTime(); timeline.add(TurnStep.Reasoning(text)) }
                        emit(AgentEvent.ReasoningDelta(text, step)); publish()
                    }
                    choice.delta.content?.takeIf { it.isNotEmpty() }?.let { text ->
                        finishReasoning()
                        val last = timeline.lastOrNull()
                        if (last is TurnStep.Answer) timeline[timeline.lastIndex] = last.copy(text = last.text + text)
                        else timeline.add(TurnStep.Answer(text))
                        emit(AgentEvent.TextDelta(text, step)); publish()
                    }
                }
            }
            finishReasoning()
            publish()
            assistant = accumulator.message()
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                timeline.clear(); timeline.addAll(committedTimeline); publish()
                if (error is ProviderHttpException && (error.status == 401 || error.status == 403)) {
                    emit(AgentEvent.AuthenticationRequired(checkpoint, error.status)); return@flow
                }
                // A successful tool turn does not replenish the run's retry budget.
                retryElapsedMs += (System.nanoTime() - requestStarted) / 1_000_000
                val pause = retryPolicy.delayMs(error, attempt, retryElapsedMs)
                if (pause == null) {
                    if (retryPolicy.isRetryable(error)) {
                        emit(AgentEvent.RecoverableWaiting(checkpoint, "Connection lost. Resume this task when you are ready.", error.toString()))
                        return@flow
                    }
                    throw error
                }
                val waitStarted = System.nanoTime()
                var remaining = pause
                while (remaining > 0) {
                    emit(AgentEvent.RetryWaiting(remaining, attempt + 1, error.toString()))
                    val slice = minOf(1000L, remaining)
                    retryWait(slice)
                    remaining -= slice
                }
                retryElapsedMs += maxOf(pause, (System.nanoTime() - waitStarted) / 1_000_000)
                attempt++
            }
            }
            val finishedAssistant = requireNotNull(assistant)
            val calls = finishedAssistant.toolCalls.orEmpty()
            if (calls.isEmpty()) {
                history.add(finishedAssistant)
                checkpoint = checkpoint.copy(assistantStep = step + 1, pendingAssistant = null, pendingToolIndex = 0, complete = true)
                save()
                emit(AgentEvent.Done)
                return@flow
            }
            if (checkpoint.pendingAssistant == null) {
                history.add(finishedAssistant)
                checkpoint = checkpoint.copy(pendingAssistant = finishedAssistant, pendingToolIndex = 0, assistantStep = step)
                save()
            }
            for (callIndex in checkpoint.pendingToolIndex until calls.size) {
                val call = calls[callIndex]
                coroutineContext.ensureActive()
                val position = timeline.size
                val started = System.nanoTime()
                var denied = false
                var recorded = false
                suspend fun record(result: ToolResult) {
                    timeline[position] = (timeline[position] as TurnStep.ToolCall).copy(
                        status = if (denied) ToolStatus.DENIED else if (result.isError) ToolStatus.ERROR else ToolStatus.DONE,
                        resultPreview = result.content.take(8000) + if (result.content.length > 8000) "\n[Result truncated]" else "",
                        durationMs = (System.nanoTime() - started) / 1_000_000)
                    history.add(ChatMessage("tool", LlmJson.encodeToString(result), toolCallId = call.id))
                    checkpoint = checkpoint.copy(pendingToolIndex = callIndex + 1)
                    save()
                    recorded = true
                }
                timeline.add(TurnStep.ToolCall(call.id, call.function.name, call.function.arguments))
                publish()
                val args = runCatching { LlmJson.parseToJsonElement(call.function.arguments) as? JsonObject }.getOrNull()
                val result = if (args == null) ToolResult("Invalid tool arguments: expected a JSON object", isError = true)
                else {
                    val decision = guard.decide(call.function.name, args, settings)
                    val allowed = when (decision) {
                        Decision.ALLOW -> true
                        Decision.DENY -> false
                        Decision.CONFIRM -> {
                            timeline[position] = (timeline[position] as TurnStep.ToolCall).copy(status = ToolStatus.WAITING)
                            publish()
                            emit(AgentEvent.ToolNeedsConfirm(call.id, call.function.name, args))
                            confirmer.confirm(call.function.name, args)
                        }
                    }
                    denied = !allowed
                    if (!allowed) ToolResult(if (decision == Decision.DENY) "Denied by safety policy or unknown tool" else "User declined this tool call", isError = true)
                    else {
                        coroutineContext.ensureActive()
                        emit(AgentEvent.ToolStarted(call.id, call.function.name))
                        timeline[position] = (timeline[position] as TurnStep.ToolCall).copy(status = ToolStatus.RUNNING)
                        publish()
                        // Once a bounded tool starts, preserve its result durably before cancellation
                        // can stop the run. Cancellation is checked before the next tool.
                        withContext(NonCancellable) {
                            val completed = try {
                                withContext(Dispatchers.IO) { registry[call.function.name]!!.run(args, context) }
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) { ToolResult("Tool failed: check the arguments, file name, size limit and workspace restrictions", isError = true) }
                            record(completed)
                            completed
                        }
                    }
                }
                if (!recorded) record(result)
                publish()
                emit(AgentEvent.ToolFinished(call.id, call.function.name, result))
                result.artifacts.forEach { emit(AgentEvent.ArtifactCreated(it)) }
            }
            checkpoint = checkpoint.copy(pendingAssistant = null, pendingToolIndex = 0, assistantStep = step + 1)
            save()
        }
        checkpoint = checkpoint.copy(complete = true)
        save()
        emit(AgentEvent.Error("Stopped after $maxSteps assistant steps. Send a new message to continue."))
    }.catch { error ->
        if (error is CancellationException) throw error
        emit(AgentEvent.Error(ErrorExplainer.explain(error)))
    }
}
