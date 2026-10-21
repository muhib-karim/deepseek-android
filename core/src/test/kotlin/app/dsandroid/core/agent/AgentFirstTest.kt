package app.dsandroid.core.agent

import app.dsandroid.core.guard.*
import app.dsandroid.core.llm.*
import app.dsandroid.core.tools.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** v1.2.0 "Agent-first": defaults, the configurable step limit, tool errors back to the model, Stop, and tool step titles. */
class AgentFirstTest {
    @get:Rule val temporary = TemporaryFolder()

    private class ScriptedClient(private val turns: List<List<ChatChunk>>) : LlmClient {
        val requests = mutableListOf<List<ChatMessage>>()
        override fun stream(messages: List<ChatMessage>, tools: List<ToolSpec>): Flow<ChatChunk> {
            requests.add(messages.toList())
            return turns[requests.lastIndex].asFlow()
        }
    }
    private fun call(name: String, arguments: String, id: String = "call1") = listOf(ChatChunk(listOf(StreamChoice(delta = StreamDelta(
        toolCalls = listOf(ToolCallDelta(0, id, function = FunctionDelta(name, arguments)))), finishReason = "tool_calls"))))
    private val answer = listOf(ChatChunk(listOf(StreamChoice(delta = StreamDelta(content = "All done"), finishReason = "stop"))))
    private fun toolMessage(client: ScriptedClient, request: Int) = client.requests[request].last { it.role == "tool" }
    private fun result(message: ChatMessage) = LlmJson.parseToJsonElement(message.content!!).jsonObject
    private fun tool(toolName: String, toolKind: ToolKind = ToolKind.READ, body: suspend (JsonObject) -> ToolResult): Tool = object : Tool {
        override val name: String = toolName
        override val description: String = toolName
        override val schema = JsonObject(emptyMap())
        override val risk = Risk.SAFE
        override val kind: ToolKind = toolKind
        override suspend fun run(args: JsonObject, ctx: ToolContext) = body(args)
    }
    private fun loop(client: LlmClient, tools: List<Tool>, maxSteps: Int = AgentLoop.DEFAULT_MAX_STEPS): AgentLoop {
        val workspace = WorkspacePath(temporary.newFolder())
        val registry = ToolRegistry(tools)
        return AgentLoop(client, registry, GuardPolicy(registry, workspace), ToolContext(workspace), Confirmer { _, _ -> false }, maxSteps = maxSteps)
    }

    @Test fun agentModeIsOnUnlessThePersonSwitchedItOff() {
        assertTrue(AgentModeDefault.resolve(stored = null, setByUser = false))
        // An older version stored "off" without the flag: migrated to on.
        assertTrue(AgentModeDefault.resolve(stored = false, setByUser = false))
        assertFalse(AgentModeDefault.resolve(stored = false, setByUser = true))
        assertTrue(AgentModeDefault.resolve(stored = true, setByUser = true))
        assertTrue(AgentModeDefault.resolve(stored = null, setByUser = true))
    }

    @Test fun unknownToolGoesBackToTheModelAsAnErrorResult() = runTest {
        val client = ScriptedClient(listOf(call("no_such_tool", "{}"), answer))
        val events = loop(client, emptyList()).run(mutableListOf(ChatMessage("user", "Do it"))).toList()
        val sent = result(toolMessage(client, 1))
        assertEquals(true, sent["isError"]!!.jsonPrimitive.boolean)
        assertTrue(sent["content"]!!.jsonPrimitive.content.contains("no tool named \"no_such_tool\""))
        assertEquals("call1", toolMessage(client, 1).toolCallId)
        assertTrue("The run finishes normally", events.last() is AgentEvent.Done)
        assertEquals(ToolStatus.DENIED, events.filterIsInstance<AgentEvent.StepsUpdated>().last().steps.filterIsInstance<TurnStep.ToolCall>().single().status)
    }

    @Test fun malformedJsonArgumentsGoBackToTheModelAsAnErrorResult() = runTest {
        var ran = false
        val client = ScriptedClient(listOf(call("echo", """{"text": "unterminated"""), answer))
        val events = loop(client, listOf(tool("echo") { ran = true; ToolResult("echo") })).run(mutableListOf(ChatMessage("user", "Echo"))).toList()
        assertFalse(ran)
        val sent = result(toolMessage(client, 1))
        assertEquals(true, sent["isError"]!!.jsonPrimitive.boolean)
        assertTrue(sent["content"]!!.jsonPrimitive.content.contains("Invalid tool arguments"))
        assertTrue(events.last() is AgentEvent.Done)
        assertEquals(ToolStatus.ERROR, events.filterIsInstance<AgentEvent.StepsUpdated>().last().steps.filterIsInstance<TurnStep.ToolCall>().single().status)
    }

    @Test fun aThrowingToolBecomesAnErrorResultAndTheLoopContinues() = runTest {
        val client = ScriptedClient(listOf(call("explode", "{}"), answer))
        val events = loop(client, listOf(tool("explode") { error("boom") })).run(mutableListOf(ChatMessage("user", "Go"))).toList()
        assertEquals(true, result(toolMessage(client, 1))["isError"]!!.jsonPrimitive.boolean)
        assertEquals(2, client.requests.size)
        assertTrue(events.last() is AgentEvent.Done)
        assertFalse(events.any { it is AgentEvent.Error })
    }

    @Test fun maxStepsIsConfigurableAndEndsWithACleanStopMessage() = runTest {
        val echo = tool("echo") { ToolResult("ok") }
        val client = ScriptedClient(List(3) { call("echo", "{}", "call$it") })
        val events = loop(client, listOf(echo), maxSteps = 3).run(mutableListOf(ChatMessage("user", "Loop"))).toList()
        assertEquals(3, client.requests.size)
        val stop = events.last() as AgentEvent.Error
        assertEquals("Stopped after 3 assistant steps. Send a new message to continue.", stop.message)
        // Completed steps stay in the timeline.
        assertEquals(3, events.filterIsInstance<AgentEvent.StepsUpdated>().last().steps.filterIsInstance<TurnStep.ToolCall>().count { it.status == ToolStatus.DONE })
    }

    @Test fun stepsCarryTheStepNumberAndTheLimitForTheCounter() = runTest {
        val echo = tool("echo") { ToolResult("ok") }
        val client = ScriptedClient(listOf(call("echo", "{}", "a"), call("echo", "{}", "b"), answer))
        val updates = loop(client, listOf(echo), maxSteps = 12).run(mutableListOf(ChatMessage("user", "Go"))).toList()
            .filterIsInstance<AgentEvent.StepsUpdated>()
        assertTrue(updates.all { it.maxSteps == 12 || it.maxSteps == 0 })
        assertEquals(listOf(1, 2, 3), updates.filter { it.maxSteps == 12 }.map { it.step }.distinct())
    }

    @Test fun stepLimitRangeIsOneToTwenty() {
        val client = ScriptedClient(emptyList())
        loop(client, emptyList(), maxSteps = 1)
        loop(client, emptyList(), maxSteps = AgentLoop.MAX_STEPS_LIMIT)
        assertThrows(IllegalArgumentException::class.java) { loop(client, emptyList(), maxSteps = 0) }
        assertThrows(IllegalArgumentException::class.java) { loop(client, emptyList(), maxSteps = 21) }
        assertEquals(1, AgentLoop.clampSteps(-4))
        assertEquals(20, AgentLoop.clampSteps(99))
        assertEquals(8, AgentLoop.clampSteps(8))
    }

    @Test fun stopMidToolCancelsAtOnceAndKeepsCompletedSteps() = runTest {
        val entered = CompletableDeferred<Unit>()
        var secondFinished = false
        val fast = tool("fast") { ToolResult("fast result") }
        val slow = tool("slow", ToolKind.NETWORK) {
            entered.complete(Unit)
            withContext(Dispatchers.IO) { Thread.sleep(50) }
            awaitCancellation()
        }
        val client = ScriptedClient(listOf(call("fast", "{}", "f1"), call("slow", "{}", "s1"), answer))
        val snapshots = mutableListOf<AgentEvent.StepsUpdated>()
        val history = mutableListOf(ChatMessage("user", "Two steps"))
        val job = launch(Dispatchers.Default) {
            loop(client, listOf(fast, slow)).run(history).collect { if (it is AgentEvent.StepsUpdated) snapshots += it }
            secondFinished = true
        }
        entered.await()
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertFalse(secondFinished)
        assertEquals("No third request after Stop", 2, client.requests.size)
        val last = snapshots.last().steps.filterIsInstance<TurnStep.ToolCall>()
        assertEquals(ToolStatus.DONE, last.first { it.name == "fast" }.status)
        assertTrue(history.any { it.role == "tool" && it.toolCallId == "f1" })
    }

    @Test fun toolStepTitlesReadLikeAChatAgent() {
        assertEquals("Searched the web: ai news", ToolStepTitle.title("web_search", """{"query":"ai news"}""", ToolStatus.DONE))
        assertEquals("Searching the web: ai news", ToolStepTitle.title("web_search", """{"query":"ai news"}""", ToolStatus.RUNNING))
        assertEquals("Read example.com", ToolStepTitle.title("web_fetch", """{"url":"https://www.example.com/report"}""", ToolStatus.DONE))
        assertEquals("Ran JavaScript", ToolStepTitle.title("code_interpreter", """{"language":"javascript","code":"1"}""", ToolStatus.DONE))
        assertEquals("Ran Python", ToolStepTitle.title("code_interpreter", """{"language":"python","code":"1"}""", ToolStatus.DONE))
        assertEquals("Created report.pdf", ToolStepTitle.title("create_pdf", """{"title":"R","file_path":"out/report.pdf"}""", ToolStatus.DONE))
        assertEquals("Created final.pdf", ToolStepTitle.title("create_pdf", """{"file_path":"report.pdf"}""", ToolStatus.DONE, "Created final.pdf\n1 page"))
        assertEquals("Creating cities.csv", ToolStepTitle.title("write_file", """{"file_path":"cities.csv"}""", ToolStatus.RUNNING))
        assertEquals("Read hello.py", ToolStepTitle.title("read", """{"file_path":"hello.py"}""", ToolStatus.DONE))
        assertEquals("Installed tabulate, rich", ToolStepTitle.title("pip_install", """{"packages":["tabulate","rich"]}""", ToolStatus.DONE))
        assertEquals("Ran data-analysis · describe_csv", ToolStepTitle.title("data_analysis__describe_csv", "{}", ToolStatus.DONE))
        assertEquals("Something new", ToolStepTitle.title("something_new", "not json", ToolStatus.ERROR))
        assertEquals(ToolStepKind.SEARCH, ToolStepTitle.kind("web_search"))
        assertEquals(ToolStepKind.CODE, ToolStepTitle.kind("code_interpreter"))
        assertEquals(ToolStepKind.FILE, ToolStepTitle.kind("create_pdf"))
        assertEquals("print(1)", ToolStepTitle.arguments("""{"code":"print(1)","language":"python"}"""))
        assertEquals("query: ai\nlimit: 3", ToolStepTitle.arguments("""{"query":"ai","limit":3}"""))
        assertTrue(ToolStepTitle.truncate("x".repeat(50), 10).endsWith("[… 40 more characters]"))
    }
}
