package app.dsandroid.core.agent

import app.dsandroid.core.deepseek.WebReply
import app.dsandroid.core.llm.*
import app.dsandroid.core.tools.ToolResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow

enum class WebPromptKind { USER, TOOL_RESULT, REPAIR }

/** Sends one message in the current web chat and streams cumulative replies; it owns the parent-message chain. */
fun interface WebChatTransport {
    fun send(prompt: String, kind: WebPromptKind): Flow<WebReply>
}

/**
 * Agent backend for account-mode web chat, which has no native tool calling. Tool calls arrive as one
 * fenced block at the end of a reply and are surfaced to [AgentLoop] as ordinary streamed tool calls;
 * results go back as the next chat message. A malformed block gets one repair prompt, then the reply
 * is shown as plain text.
 */
class WebToolProtocolClient(
    private val transport: WebChatTransport,
    private val instructions: (List<ToolSpec>) -> String = { ToolCallProtocol.systemPrompt(it) },
) : LlmClient {
    private var callCount = 0

    override fun stream(messages: List<ChatMessage>, tools: List<ToolSpec>): Flow<ChatChunk> = flow {
        var (prompt, kind) = nextPrompt(messages, tools)
        val knownTools = tools.map { it.function.name }.toSet()
        var repaired = false
        var shownAny = false
        while (true) {
            val filter = ToolCallProtocol.VisibleText()
            var shownText = 0
            var shownThinking = 0
            var separate = shownAny
            var final: WebReply? = null
            suspend fun FlowCollector<ChatChunk>.show(text: String) {
                if (text.isEmpty()) return
                content(if (separate) "\n\n$text" else text)
                separate = false
                shownAny = true
            }
            transport.send(prompt, kind).collect { reply ->
                final = reply
                if (reply.thinking.length > shownThinking) {
                    reasoning(reply.thinking.substring(shownThinking))
                    shownThinking = reply.thinking.length
                }
                val visible = filter.visible(reply.response)
                if (visible.length > shownText) {
                    show(visible.substring(shownText))
                    shownText = visible.length
                }
            }
            val text = final?.response.orEmpty()
            when (val parsed = ToolCallProtocol.parse(text, knownTools)) {
                is ToolCallParse.Plain -> {
                    show(parsed.text.drop(shownText))
                    finish("stop")
                    return@flow
                }
                is ToolCallParse.Call -> {
                    show(parsed.prose.drop(shownText))
                    callCount++
                    val call = ToolCallDelta(0, "web_call_$callCount", "function", FunctionDelta(parsed.name, parsed.arguments.toString()))
                    emit(ChatChunk(listOf(StreamChoice(delta = StreamDelta(toolCalls = listOf(call)), finishReason = "tool_calls"))))
                    return@flow
                }
                is ToolCallParse.Malformed -> {
                    if (!repaired) {
                        repaired = true
                        prompt = ToolCallProtocol.repairPrompt(parsed.reason)
                        kind = WebPromptKind.REPAIR
                        continue
                    }
                    show(text.drop(shownText))
                    finish("stop")
                    return@flow
                }
            }
        }
    }

    private suspend fun FlowCollector<ChatChunk>.content(text: String) =
        emit(ChatChunk(listOf(StreamChoice(delta = StreamDelta(content = text)))))

    private suspend fun FlowCollector<ChatChunk>.reasoning(text: String) =
        emit(ChatChunk(listOf(StreamChoice(delta = StreamDelta(reasoningContent = text)))))

    private suspend fun FlowCollector<ChatChunk>.finish(reason: String) =
        emit(ChatChunk(listOf(StreamChoice(finishReason = reason))))

    /** A new user turn carries the instructions; after tools run, only their results are sent. */
    private fun nextPrompt(messages: List<ChatMessage>, tools: List<ToolSpec>): Pair<String, WebPromptKind> {
        val last = messages.lastOrNull() ?: throw IllegalArgumentException("Nothing to send")
        return when (last.role) {
            "user" -> {
                val request = last.content ?: last.parts.orEmpty().mapNotNull { it.text }.joinToString("\n")
                val preamble = if (tools.isEmpty()) "" else instructions(tools) + "\n\nUser request:\n"
                (preamble + request) to WebPromptKind.USER
            }
            "tool" -> {
                val assistantIndex = messages.indexOfLast { it.role == "assistant" }
                val calls = messages.getOrNull(assistantIndex)?.toolCalls.orEmpty()
                val results = messages.drop(assistantIndex + 1).filter { it.role == "tool" }
                results.joinToString("\n\n") { message ->
                    val name = calls.firstOrNull { it.id == message.toolCallId }?.function?.name ?: "tool"
                    val result = runCatching { LlmJson.decodeFromString(ToolResult.serializer(), message.content.orEmpty()) }.getOrNull()
                    ToolCallProtocol.formatResult(name, result != null && !result.isError, result?.content ?: message.content.orEmpty())
                } to WebPromptKind.TOOL_RESULT
            }
            else -> throw IllegalArgumentException("Unexpected ${last.role} message at the end of the history")
        }
    }
}
