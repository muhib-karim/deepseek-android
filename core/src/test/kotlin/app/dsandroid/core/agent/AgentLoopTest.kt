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

class AgentLoopTest {
    @get:Rule val temporary = TemporaryFolder()
    private class FakeLlmClient(private val turns: List<List<ChatChunk>>) : LlmClient {
        val requests = mutableListOf<List<ChatMessage>>()
        override fun stream(messages: List<ChatMessage>, tools: List<ToolSpec>): Flow<ChatChunk> {
            requests.add(messages.toList())
            return turns[requests.lastIndex].asFlow()
        }
    }
    private fun toolTurn(name: String, arguments: String) = listOf(ChatChunk(listOf(StreamChoice(delta = StreamDelta(
        reasoningContent = "Make a file", toolCalls = listOf(ToolCallDelta(0, "call1", function = FunctionDelta(name, arguments)))), finishReason = "tool_calls"))))
    private val answer = listOf(ChatChunk(listOf(StreamChoice(delta = StreamDelta(content = "Finished"), finishReason = "stop"))))

    @Test fun runsToolReturnsResultToModelAndEmitsArtifact() = runTest {
        val client = FakeLlmClient(listOf(toolTurn("create_markdown", """{"fileName":"note.md","content":"Hello"}"""), answer))
        val workspace = WorkspacePath(temporary.newFolder())
        val registry = ToolRegistry(listOf(CreateMarkdownTool()))
        val loop = AgentLoop(client, registry, GuardPolicy(registry, workspace), ToolContext(workspace), Confirmer { _, _ -> fail("Unexpected confirmation"); false })
        val history = mutableListOf(ChatMessage("user", "Write a note"))
        val events = loop.run(history).toList()
        assertEquals("Hello", workspace.resolve("note.md").readText())
        assertTrue(events.any { it is AgentEvent.ReasoningDelta })
        assertTrue(events.any { it is AgentEvent.ToolStarted })
        assertTrue(events.any { it is AgentEvent.ArtifactCreated && it.artifact.path == "note.md" })
        assertTrue(events.last() is AgentEvent.Done)
        assertEquals("call1", client.requests[1].last().toolCallId)
        assertEquals("tool", client.requests[1].last().role)
        assertEquals("Finished", history.last().content)
    }
    @Test fun declinedConfirmationNeverRunsAndExplainsDenialToModel() = runTest {
        var ran = false
        val tool = object : Tool {
            override val name = "action"
            override val description = "Requires consent"
            override val schema = JsonObject(emptyMap())
            override val risk = Risk.CONFIRM
            override val kind = ToolKind.ACTION
            override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult { ran = true; return ToolResult("ran") }
        }
        val client = FakeLlmClient(listOf(toolTurn("action", "{}"), answer))
        val workspace = WorkspacePath(temporary.newFolder())
        val registry = ToolRegistry(listOf(tool))
        val loop = AgentLoop(client, registry, GuardPolicy(registry, workspace), ToolContext(workspace), Confirmer { name, args ->
            assertEquals("action", name); assertEquals("{}", args.toString()); false
        })
        val events = loop.run(mutableListOf(ChatMessage("user", "Do it"))).toList()
        assertFalse(ran)
        assertTrue(events.any { it is AgentEvent.ToolNeedsConfirm })
        assertFalse(events.any { it is AgentEvent.ToolStarted })
        assertTrue(client.requests[1].last().content!!.contains("declined", ignoreCase = true))
    }
    @Test fun unknownToolAndInvalidArgumentsReturnToolErrors() = runTest {
        val workspace = WorkspacePath(temporary.newFolder())
        val registry = ToolRegistry(emptyList())
        for (arguments in listOf("{}", "not json")) {
            val client = FakeLlmClient(listOf(toolTurn("unknown", arguments), answer))
            AgentLoop(client, registry, GuardPolicy(registry, workspace), ToolContext(workspace), Confirmer { _, _ -> false })
                .run(mutableListOf(ChatMessage("user", "Do it"))).toList()
            assertTrue(client.requests[1].last().content!!.contains("isError"))
            assertTrue(client.requests[1].last().content!!.contains("true"))
        }
    }
    @Test fun boundsStepsAndPropagatesCancellation() = runTest {
        val workspace = WorkspacePath(temporary.newFolder())
        val registry = ToolRegistry(emptyList())
        val repeated = FakeLlmClient(List(8) { toolTurn("unknown", "{}") })
        val events = AgentLoop(repeated, registry, GuardPolicy(registry, workspace), ToolContext(workspace), Confirmer { _, _ -> false })
            .run(mutableListOf(ChatMessage("user", "Loop"))).toList()
        assertEquals(8, repeated.requests.size)
        assertTrue(events.last() is AgentEvent.Error)
        val waiting = object : LlmClient {
            override fun stream(messages: List<ChatMessage>, tools: List<ToolSpec>): Flow<ChatChunk> = flow { awaitCancellation() }
        }
        val history = mutableListOf(ChatMessage("user", "Wait"))
        val job = launch { AgentLoop(waiting, registry, GuardPolicy(registry, workspace), ToolContext(workspace), Confirmer { _, _ -> false }).run(history).collect() }
        yield()
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertEquals(1, history.size)
    }
}
