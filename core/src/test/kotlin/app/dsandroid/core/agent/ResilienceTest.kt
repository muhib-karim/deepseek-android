package app.dsandroid.core.agent

import app.dsandroid.core.llm.*
import app.dsandroid.core.auth.*
import java.net.UnknownHostException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Test

class ResilienceTest {
    @Test fun retriesOnlyTransientFailuresAndHonoursBudgetAndRetryAfter() {
        val policy = RetryPolicy(jitter = { 1.0 })
        assertEquals(1000L, policy.delayMs(UnknownHostException(), 0, 0))
        assertEquals(2000L, policy.delayMs(UnknownHostException(), 1, 0))
        assertEquals(30000L, policy.delayMs(UnknownHostException(), 10, 0))
        for (status in listOf(400, 401, 402, 403)) assertNull(policy.delayMs(ProviderHttpException(status, ""), 0, 0))
        assertEquals(9000L, policy.delayMs(ProviderHttpException(429, "", "9"), 0, 0))
        assertNull(policy.delayMs(UnknownHostException(), 0, 300000))
        assertNull(policy.delayMs(kotlinx.coroutines.CancellationException(), 0, 0))
    }
    @Test fun retriesServerErrorsAndHttpDateButNeverOutrunsBudget() {
        val policy = RetryPolicy(jitter = { 1.0 }, nowMs = { 0 })
        assertEquals(1000L, policy.delayMs(ProviderHttpException(503, ""), 0, 0))
        assertEquals(10000L, policy.delayMs(ProviderHttpException(429, "", "Thu, 1 Jan 1970 00:00:10 GMT"), 0, 0))
        assertNull(policy.delayMs(ProviderHttpException(429, "", "301"), 0, 0))
        assertNull(policy.delayMs(IllegalStateException(), 0, 0))
    }
    @Test fun checkpointRoundTripsPendingBatchAndCompletedResults() {
        val assistant = ChatMessage("assistant", toolCalls = listOf(ToolCall("a", function = FunctionCall("create_file", "{}")), ToolCall("b", function = FunctionCall("create_file", "{}"))))
        val checkpoint = RunCheckpoint(runId = "run", conversationId = "conversation", model = "model", messages = listOf(ChatMessage("user", "hello"), assistant, ChatMessage("tool", "result", toolCallId = "a")), steps = listOf(TurnStep.Reasoning("thought", complete = true), TurnStep.ToolCall("a", "create_file", "{}", ToolStatus.DONE)), pendingAssistant = assistant, pendingToolIndex = 1)
        assertEquals(checkpoint, LlmJson.decodeFromString<RunCheckpoint>(LlmJson.encodeToString(checkpoint)))
    }
    @Test fun expiredSessionResumesOnlyAfterSameAccountRenewsCredentials() {
        val machine = SessionAuthMachine()
        machine.signedIn("account", "version1")
        machine.rejected(401)
        assertFalse(machine.canResume)
        assertFalse(machine.renew("another", "version2"))
        assertFalse(machine.renew("account", "version1"))
        assertTrue(machine.renew("account", "version2"))
        assertTrue(machine.canResume)
        machine.rejected(403)
        assertFalse(machine.canResume)
    }
}

class AgentResumeTest {
    @org.junit.Rule @JvmField val temporary = org.junit.rules.TemporaryFolder()
    private class Store : CheckpointStore {
        var checkpoint: RunCheckpoint? = null
        override suspend fun save(checkpoint: RunCheckpoint) { this.checkpoint = checkpoint }
        override suspend fun load(runId: String) = checkpoint?.takeIf { it.runId == runId }
        override suspend fun delete(runId: String) { checkpoint = null }
    }
    @Test fun retryBudgetIsSharedAcrossAssistantToolTurns() = kotlinx.coroutines.test.runTest {
        val workspace = app.dsandroid.core.guard.WorkspacePath(temporary.newFolder())
        val registry = app.dsandroid.core.tools.ToolRegistry(emptyList())
        var requests = 0
        var waitedMs = 0L
        val client = object : LlmClient {
            override fun stream(messages: List<ChatMessage>, tools: List<ToolSpec>) = kotlinx.coroutines.flow.flow {
                requests++
                if (requests != 2) throw ProviderHttpException(429, "", "100")
                emit(ChatChunk(listOf(StreamChoice(delta = StreamDelta(toolCalls = listOf(ToolCallDelta(0, "one", function = FunctionDelta("unknown", "{}"))))))))
            }
        }
        val events = mutableListOf<AgentEvent>()
        AgentLoop(client, registry, app.dsandroid.core.guard.GuardPolicy(registry, workspace), app.dsandroid.core.tools.ToolContext(workspace), Confirmer { _, _ -> false },
            retryPolicy = RetryPolicy(budgetMs = 150_000, jitter = { 1.0 }), retryWait = { waitedMs += it })
            .run(mutableListOf(ChatMessage("user", "continue"))).collect { events.add(it) }
        assertEquals(100_000L, waitedMs)
        assertEquals(3, requests)
        assertTrue(events.last() is AgentEvent.RecoverableWaiting)
        val checkpoint = (events.last() as AgentEvent.RecoverableWaiting).checkpoint
        assertEquals(1, checkpoint.messages.count { it.role == "tool" })
    }
    @Test fun cancellationAfterToolSideEffectStillPersistsResult() = kotlinx.coroutines.test.runTest {
        val workspace = app.dsandroid.core.guard.WorkspacePath(temporary.newFolder())
        val sideEffect = kotlinx.coroutines.CompletableDeferred<Unit>()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        var executions = 0
        val tool = object : app.dsandroid.core.tools.Tool {
            override val name = "count"
            override val description = "Count"
            override val schema = kotlinx.serialization.json.JsonObject(emptyMap())
            override val risk = app.dsandroid.core.tools.Risk.SAFE
            override val kind = app.dsandroid.core.tools.ToolKind.READ
            override suspend fun run(args: kotlinx.serialization.json.JsonObject, ctx: app.dsandroid.core.tools.ToolContext): app.dsandroid.core.tools.ToolResult {
                executions++
                sideEffect.complete(Unit)
                release.await()
                return app.dsandroid.core.tools.ToolResult("committed")
            }
        }
        val registry = app.dsandroid.core.tools.ToolRegistry(listOf(tool))
        val client = object : LlmClient {
            override fun stream(messages: List<ChatMessage>, tools: List<ToolSpec>) = kotlinx.coroutines.flow.flow {
                emit(ChatChunk(listOf(StreamChoice(delta = StreamDelta(toolCalls = listOf(ToolCallDelta(0, "one", function = FunctionDelta("count", "{}"))))))))
            }
        }
        val store = Store()
        val job = launch {
            AgentLoop(client, registry, app.dsandroid.core.guard.GuardPolicy(registry, workspace), app.dsandroid.core.tools.ToolContext(workspace), Confirmer { _, _ -> false }, checkpointStore = store).run(mutableListOf(ChatMessage("user", "count"))).collect { }
        }
        sideEffect.await()
        job.cancel()
        release.complete(Unit)
        job.join()
        assertTrue(job.isCancelled)
        assertEquals(1, executions)
        assertEquals(1, store.checkpoint!!.pendingToolIndex)
        assertEquals("tool", store.checkpoint!!.messages.last().role)
        assertEquals(ToolStatus.DONE, store.checkpoint!!.steps.filterIsInstance<TurnStep.ToolCall>().single().status)
    }
    @Test fun cancellationNeverBecomesRetryAndLeavesCommittedCheckpoint() = kotlinx.coroutines.test.runTest {
        val workspace = app.dsandroid.core.guard.WorkspacePath(temporary.newFolder())
        val registry = app.dsandroid.core.tools.ToolRegistry(emptyList())
        val store = Store()
        val client = object : LlmClient {
            override fun stream(messages: List<ChatMessage>, tools: List<ToolSpec>) = kotlinx.coroutines.flow.flow<ChatChunk> {
                emit(ChatChunk(listOf(StreamChoice(delta = StreamDelta(reasoningContent = "unfinished")))))
                throw kotlinx.coroutines.CancellationException("cancel")
            }
        }
        var cancelled = false
        val events = mutableListOf<AgentEvent>()
        try {
            AgentLoop(client, registry, app.dsandroid.core.guard.GuardPolicy(registry, workspace), app.dsandroid.core.tools.ToolContext(workspace), Confirmer { _, _ -> false }, checkpointStore = store).run(mutableListOf(ChatMessage("user", "wait"))).collect { events.add(it) }
        } catch (_: kotlinx.coroutines.CancellationException) { cancelled = true }
        assertTrue(cancelled)
        assertFalse(events.any { it is AgentEvent.RetryWaiting || it is AgentEvent.RecoverableWaiting || it is AgentEvent.Error })
        assertTrue(store.checkpoint!!.steps.isEmpty())
        assertEquals(1, store.checkpoint!!.messages.size)
    }
    @Test fun resumesOnlyUnfinishedToolsInInterruptedBatch() = kotlinx.coroutines.test.runTest {
        val workspace = app.dsandroid.core.guard.WorkspacePath(temporary.newFolder())
        val ran = mutableListOf<String>()
        val tool = object : app.dsandroid.core.tools.Tool {
            override val name = "count"
            override val description = "Count"
            override val schema = kotlinx.serialization.json.JsonObject(emptyMap())
            override val risk = app.dsandroid.core.tools.Risk.SAFE
            override val kind = app.dsandroid.core.tools.ToolKind.READ
            override suspend fun run(args: kotlinx.serialization.json.JsonObject, ctx: app.dsandroid.core.tools.ToolContext): app.dsandroid.core.tools.ToolResult {
                ran.add("second"); return app.dsandroid.core.tools.ToolResult("done")
            }
        }
        val registry = app.dsandroid.core.tools.ToolRegistry(listOf(tool))
        val assistant = ChatMessage("assistant", toolCalls = listOf(ToolCall("first", function = FunctionCall("count", "{}")), ToolCall("second", function = FunctionCall("count", "{}"))))
        val checkpoint = RunCheckpoint("batch", messages = listOf(ChatMessage("user", "two"), assistant, ChatMessage("tool", "done", toolCallId = "first")), steps = listOf(TurnStep.ToolCall("first", "count", "{}", ToolStatus.DONE)), pendingAssistant = assistant, pendingToolIndex = 1)
        val client = object : LlmClient {
            override fun stream(messages: List<ChatMessage>, tools: List<ToolSpec>) = kotlinx.coroutines.flow.flow {
                assertEquals(listOf("first", "second"), messages.filter { it.role == "tool" }.map { it.toolCallId })
                emit(ChatChunk(listOf(StreamChoice(delta = StreamDelta(content = "Complete")))))
            }
        }
        val store = Store()
        AgentLoop(client, registry, app.dsandroid.core.guard.GuardPolicy(registry, workspace), app.dsandroid.core.tools.ToolContext(workspace), Confirmer { _, _ -> false }, checkpointStore = store).resume(checkpoint).collect { }
        assertEquals(listOf("second"), ran)
        assertTrue(store.checkpoint!!.complete)
        assertEquals(2, store.checkpoint!!.steps.filterIsInstance<TurnStep.ToolCall>().size)
    }
    @Test fun dnsFailureAfterToolResumesWithoutRunningItAgain() = kotlinx.coroutines.test.runTest {
        val workspace = app.dsandroid.core.guard.WorkspacePath(temporary.newFolder())
        var executions = 0
        val tool = object : app.dsandroid.core.tools.Tool {
            override val name = "count"
            override val description = "Count"
            override val schema = kotlinx.serialization.json.JsonObject(emptyMap())
            override val risk = app.dsandroid.core.tools.Risk.SAFE
            override val kind = app.dsandroid.core.tools.ToolKind.READ
            override suspend fun run(args: kotlinx.serialization.json.JsonObject, ctx: app.dsandroid.core.tools.ToolContext): app.dsandroid.core.tools.ToolResult {
                executions++; return app.dsandroid.core.tools.ToolResult("counted")
            }
        }
        val registry = app.dsandroid.core.tools.ToolRegistry(listOf(tool))
        var requests = 0
        val client = object : LlmClient {
            override fun stream(messages: List<ChatMessage>, tools: List<ToolSpec>) = kotlinx.coroutines.flow.flow {
                requests++
                if (requests == 1) emit(ChatChunk(listOf(StreamChoice(delta = StreamDelta(toolCalls = listOf(ToolCallDelta(0, "one", function = FunctionDelta("count", "{}"))))))))
                else { emit(ChatChunk(listOf(StreamChoice(delta = StreamDelta(content = "discard partial"))))); throw UnknownHostException() }
            }
        }
        val store = Store()
        val loop = AgentLoop(client, registry, app.dsandroid.core.guard.GuardPolicy(registry, workspace), app.dsandroid.core.tools.ToolContext(workspace), Confirmer { _, _ -> false }, checkpointStore = store, retryPolicy = RetryPolicy(budgetMs = 0))
        val events = mutableListOf<AgentEvent>()
        loop.run(mutableListOf(ChatMessage("user", "count"))).collect { events.add(it) }
        assertTrue(events.last() is AgentEvent.RecoverableWaiting)
        assertEquals(1, executions)
        assertFalse(store.checkpoint!!.steps.any { it is TurnStep.Answer })
        val healthy = object : LlmClient {
            override fun stream(messages: List<ChatMessage>, tools: List<ToolSpec>) = kotlinx.coroutines.flow.flow {
                assertEquals("tool", messages.last().role)
                emit(ChatChunk(listOf(StreamChoice(delta = StreamDelta(content = "Final answer")))))
            }
        }
        val history = mutableListOf<ChatMessage>()
        val resumed = AgentLoop(healthy, registry, app.dsandroid.core.guard.GuardPolicy(registry, workspace), app.dsandroid.core.tools.ToolContext(workspace), Confirmer { _, _ -> false }, checkpointStore = store)
        resumed.resume(store.checkpoint!!, history).collect { }
        assertEquals(1, executions)
        assertEquals("Final answer", history.last().content)
        assertTrue(store.checkpoint!!.complete)
    }
}
