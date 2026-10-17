package app.dsandroid.core.agent

import app.dsandroid.core.guard.*
import app.dsandroid.core.llm.*
import app.dsandroid.core.tools.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AgentModeTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun chatModeAdvertisesNoToolsAndNeverExecutesUnsolicitedCalls() = runTest {
        var ran = false
        var offeredTools: List<ToolSpec>? = null
        var confirmations = 0
        val tool = object : Tool {
            override val name = "network"
            override val description = "Network"
            override val schema = JsonObject(emptyMap())
            override val risk = Risk.SAFE
            override val kind = ToolKind.NETWORK
            override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult { ran = true; return ToolResult("done") }
        }
        val client = object : LlmClient {
            override fun stream(messages: List<ChatMessage>, tools: List<ToolSpec>) = flow {
                offeredTools = tools
                emit(ChatChunk(listOf(StreamChoice(delta = StreamDelta(toolCalls = listOf(
                    ToolCallDelta(0, "id", function = FunctionDelta("network", "{}"))))))))
            }
        }
        val workspace = WorkspacePath(temporary.newFolder())
        val registry = ToolRegistry(listOf(tool))
        val events = AgentLoop(client, registry, GuardPolicy(registry, workspace), ToolContext(workspace),
            Confirmer { _, _ -> confirmations++; false }, runConfig = RunConfig(agentEnabled = false))
            .run(mutableListOf(ChatMessage("user", "hello"))).toList()
        assertFalse(ran)
        assertEquals(emptyList<ToolSpec>(), offeredTools)
        assertEquals(0, confirmations)
        assertTrue(events.last() is AgentEvent.Error)
    }

    @Test fun cancellationStopsNetworkToolWithoutRetryOrNextTool() = runTest {
        val entered = CompletableDeferred<Unit>()
        val exited = CompletableDeferred<Unit>()
        var executions = 0
        var requests = 0
        val events = mutableListOf<AgentEvent>()
        val tool = object : Tool {
            override val name = "network"
            override val description = "Network"
            override val schema = JsonObject(emptyMap())
            override val risk = Risk.SAFE
            override val kind = ToolKind.NETWORK
            override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
                executions++
                entered.complete(Unit)
                try { awaitCancellation() } finally { exited.complete(Unit) }
            }
        }
        val client = object : LlmClient {
            override fun stream(messages: List<ChatMessage>, tools: List<ToolSpec>): Flow<ChatChunk> {
                requests++
                return flowOf(ChatChunk(listOf(StreamChoice(delta = StreamDelta(toolCalls = listOf(
                    ToolCallDelta(0, "id", function = FunctionDelta("network", "{}")),
                    ToolCallDelta(1, "next", function = FunctionDelta("network", "{}")),
                ))))))
            }
        }
        val workspace = WorkspacePath(temporary.newFolder())
        val registry = ToolRegistry(listOf(tool))
        val job = launch {
            AgentLoop(client, registry, GuardPolicy(registry, workspace), ToolContext(workspace), Confirmer { _, _ -> true })
                .run(mutableListOf(ChatMessage("user", "fetch"))).collect { events.add(it) }
        }
        entered.await()
        job.cancelAndJoin()
        assertTrue(exited.isCompleted)
        assertEquals(1, executions)
        assertEquals(1, requests)
        assertFalse(events.any { it is AgentEvent.RetryWaiting || it is AgentEvent.Done })
    }
}
