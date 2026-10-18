package app.dsandroid.core.agent

import app.dsandroid.core.deepseek.WebReply
import app.dsandroid.core.guard.GuardPolicy
import app.dsandroid.core.guard.WorkspacePath
import app.dsandroid.core.llm.ChatMessage
import app.dsandroid.core.tools.*
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** AgentLoop with the account-mode text protocol backend; the web chat is a scripted fake. */
class WebAgentLoopTest {
    @get:Rule val temporary = TemporaryFolder()
    private val fence = "```"

    private class FakeTransport(private val replies: (Int) -> String) : WebChatTransport {
        val prompts = mutableListOf<Pair<String, WebPromptKind>>()
        var parent = 0L
        override fun send(prompt: String, kind: WebPromptKind): Flow<WebReply> = flow {
            prompts += prompt to kind
            val text = replies(prompts.size - 1)
            // Cumulative snapshots like the web stream reducer, split mid-fence.
            val cut = text.length / 2
            emit(WebReply(response = text.substring(0, cut)))
            parent += 2
            emit(WebReply(response = text, responseMessageId = parent, finished = true, requestMessageId = parent - 1))
        }
    }

    private class EchoTool : Tool {
        var runs = 0
        override val name = "echo"
        override val description = "Echo text back."
        override val schema = objectSchema(mapOf("text" to stringSchema("Text to echo")))
        override val risk = Risk.SAFE
        override val kind = ToolKind.READ
        override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult { runs++; return ToolResult("echo: ${args.stringArg("text")}") }
    }

    private class SlowTool : Tool {
        override val name = "slow"
        override val description = "Never finishes."
        override val schema = objectSchema(emptyMap())
        override val risk = Risk.SAFE
        override val kind = ToolKind.COMPUTE
        override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult = awaitCancellation()
    }

    private fun block(name: String, arguments: String) = "$fence" + "tool\n{\"name\": \"$name\", \"arguments\": $arguments}\n$fence"

    private fun loop(transport: FakeTransport, vararg tools: Tool, maxSteps: Int = 8, timeoutMs: Long = AgentLoop.DEFAULT_TOOL_TIMEOUT_MS): AgentLoop {
        val workspace = WorkspacePath(temporary.newFolder())
        val registry = ToolRegistry(tools.toList())
        return AgentLoop(WebToolProtocolClient(transport), registry, GuardPolicy(registry, workspace), ToolContext(workspace),
            Confirmer { _, _ -> fail("No confirmation expected"); false }, maxSteps = maxSteps, toolTimeoutMs = timeoutMs)
    }

    private fun answers(events: List<AgentEvent>) = events.filterIsInstance<AgentEvent.StepsUpdated>().last().steps

    @Test fun toolResultRoundTripsAsTheNextChatMessage() = runBlocking<Unit> {
        val transport = FakeTransport { index ->
            if (index == 0) "I'll echo that.\n" + block("echo", """{"text": "hi"}""") else "The tool said: echo: hi"
        }
        val echo = EchoTool()
        val history = mutableListOf(ChatMessage("user", "Say hi through the tool"))
        val events = loop(transport, echo).run(history).toList()

        assertTrue(events.last() is AgentEvent.Done)
        assertEquals(1, echo.runs)
        assertEquals(listOf(WebPromptKind.USER, WebPromptKind.TOOL_RESULT), transport.prompts.map { it.second })
        val first = transport.prompts[0].first
        assertTrue(first.startsWith("[Agent mode"))
        assertTrue(first.contains("- echo(text: string): Echo text back."))
        assertTrue(first.endsWith("User request:\nSay hi through the tool"))
        val result = transport.prompts[1].first
        assertTrue(result.startsWith("${fence}tool_result\n"))
        val json = Json.parseToJsonElement(result.lines()[1]).jsonObject
        assertEquals("echo", json["name"]!!.jsonPrimitive.content)
        assertTrue(json["ok"]!!.jsonPrimitive.boolean)
        assertEquals("echo: hi", json["content"]!!.jsonPrimitive.content)

        val steps = answers(events)
        assertEquals("I'll echo that.", (steps[0] as TurnStep.Answer).text.trim())
        val call = steps[1] as TurnStep.ToolCall
        assertEquals("echo", call.name)
        assertEquals(ToolStatus.DONE, call.status)
        assertEquals("The tool said: echo: hi", (steps[2] as TurnStep.Answer).text)
        assertFalse("The raw tool block must never be shown", steps.filterIsInstance<TurnStep.Answer>().any { it.text.contains(fence) })
        assertEquals("The tool said: echo: hi", history.last().content)
        assertEquals("web_call_1", history[1].toolCalls!!.single().id)
        assertEquals("tool", history[2].role)
    }

    @Test fun stepLimitStopsAModelThatNeverFinishes() = runBlocking<Unit> {
        val transport = FakeTransport { block("echo", """{"text": "again"}""") }
        val echo = EchoTool()
        val events = loop(transport, echo).run(mutableListOf(ChatMessage("user", "Loop forever"))).toList()
        assertEquals(8, transport.prompts.size)
        assertEquals(8, echo.runs)
        val error = events.last() as AgentEvent.Error
        assertTrue(error.message.contains("8 assistant steps"))
    }

    @Test fun slowToolTimesOutAndTheModelIsTold() = runBlocking<Unit> {
        val transport = FakeTransport { index -> if (index == 0) block("slow", "{}") else "It timed out, sorry." }
        val events = loop(transport, SlowTool(), timeoutMs = 150).run(mutableListOf(ChatMessage("user", "Be slow"))).toList()
        assertTrue(events.last() is AgentEvent.Done)
        val call = answers(events).filterIsInstance<TurnStep.ToolCall>().single()
        assertEquals(ToolStatus.ERROR, call.status)
        assertTrue(call.resultPreview.contains("timed out after 150 ms"))
        val json = Json.parseToJsonElement(transport.prompts[1].first.lines()[1]).jsonObject
        assertFalse(json["ok"]!!.jsonPrimitive.boolean)
    }

    @Test fun malformedBlockGetsOneRepairPromptThenRuns() = runBlocking<Unit> {
        val transport = FakeTransport { index ->
            when (index) {
                0 -> "Working on it.\n$fence" + "tool\n{\"name\": \"echo\", \"arguments\": {\"text\": \"hi\"}\n$fence"
                1 -> block("echo", """{"text": "fixed"}""")
                else -> "Done."
            }
        }
        val echo = EchoTool()
        val events = loop(transport, echo).run(mutableListOf(ChatMessage("user", "Echo"))).toList()
        assertEquals(listOf(WebPromptKind.USER, WebPromptKind.REPAIR, WebPromptKind.TOOL_RESULT), transport.prompts.map { it.second })
        assertTrue(transport.prompts[1].first.contains("incomplete"))
        assertEquals(1, echo.runs)
        assertTrue(transport.prompts[2].first.contains("echo: fixed"))
        assertTrue(events.last() is AgentEvent.Done)
        val shown = answers(events).filterIsInstance<TurnStep.Answer>().joinToString("") { it.text }
        assertTrue(shown.contains("Working on it."))
        assertFalse(shown.contains(fence))
    }

    @Test fun secondMalformedReplyIsShownAsPlainText() = runBlocking<Unit> {
        val broken = "$fence" + "tool\n{\"name\": \"echo\" \"arguments\": {}}\n$fence"
        val transport = FakeTransport { index -> if (index == 0) "First try.\n$broken" else "Second try.\n$broken" }
        val echo = EchoTool()
        val history = mutableListOf(ChatMessage("user", "Echo"))
        val events = loop(transport, echo).run(history).toList()
        assertEquals(2, transport.prompts.size)
        assertEquals(0, echo.runs)
        assertTrue(events.last() is AgentEvent.Done)
        val text = history.last().content.orEmpty()
        assertTrue(text.contains("First try."))
        assertTrue(text.contains("Second try.\n$broken"))
    }

    @Test fun deepThinkReasoningIsStreamedAsReasoningSteps() = runBlocking<Unit> {
        val transport = object : WebChatTransport {
            override fun send(prompt: String, kind: WebPromptKind): Flow<WebReply> = flow {
                emit(WebReply(thinking = "Consider"))
                emit(WebReply(thinking = "Consider the request.", response = "Answer", responseMessageId = 2, finished = true))
            }
        }
        val workspace = WorkspacePath(temporary.newFolder())
        val registry = ToolRegistry(listOf(EchoTool()))
        val events = AgentLoop(WebToolProtocolClient(transport), registry, GuardPolicy(registry, workspace), ToolContext(workspace),
            Confirmer { _, _ -> false }).run(mutableListOf(ChatMessage("user", "Think"))).toList()
        val steps = answers(events)
        assertEquals("Consider the request.", (steps[0] as TurnStep.Reasoning).text)
        assertEquals("Answer", (steps[1] as TurnStep.Answer).text)
    }
}
