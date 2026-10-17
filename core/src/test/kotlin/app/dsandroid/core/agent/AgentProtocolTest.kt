package app.dsandroid.core.agent

import app.dsandroid.core.guard.*
import app.dsandroid.core.llm.*
import app.dsandroid.core.tools.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AgentProtocolTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun chunk(delta: StreamDelta, finish: String? = null) = ChatChunk(listOf(StreamChoice(delta = delta, finishReason = finish)))
    private fun call(name: String, args: String, id: String = name) = chunk(StreamDelta(toolCalls = listOf(
        ToolCallDelta(0, id, function = FunctionDelta(name, args)))), "tool_calls")
    private fun response(vararg chunks: ChatChunk) = MockResponse().setHeader("Content-Type", "text/event-stream")
        .setBody(chunks.joinToString("") { "data: ${LlmJson.encodeToString(it)}\n\n" } + "data: [DONE]\n\n")

    @Test fun realStreamingRequestsSearchFetchDenialAndAnswerCarryToolIdsAndReasoning() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(response(chunk(StreamDelta(reasoningContent = "Find evidence")),
                chunk(StreamDelta(toolCalls = listOf(ToolCallDelta(0, "search1", function = FunctionDelta("web_search", "{\"query\":"))))),
                chunk(StreamDelta(toolCalls = listOf(ToolCallDelta(0, function = FunctionDelta(arguments = "\"exact query\"}")))), "tool_calls")))
            server.enqueue(response(call("web_fetch", """{"url":"https://example.org/report"}""", "fetch1")))
            server.enqueue(response(call("web_fetch", """{"url":"https://example.org/report?private=value"}""", "fetch2")))
            server.enqueue(response(chunk(StreamDelta(content = "Evidence [1]."), "stop")))
            val confirmations = mutableListOf<Pair<String, JsonObject>>()
            var fetches = 0
            val search = WebSearchTool(fetchPage = { "<a class='result__a' href='https://example.org/report'>Report</a>" })
            val fetch = WebFetchTool { fetches++; "<title>Report</title><p>Evidence</p>" }
            val registry = ToolRegistry(listOf(search, fetch))
            val workspace = WorkspacePath(temporary.newFolder())
            val context = ToolContext(workspace)
            val client = OpenAiCompatibleClient(server.url("/v1").toString(), "deepseek-chat", CustomEndpointAuth(""), ClientIdentity("test", "en", 0))
            val history = mutableListOf(ChatMessage("user", "previous"), ChatMessage("assistant", "previous answer", "old reasoning"), ChatMessage("user", "research"))
            val events = AgentLoop(client, registry, GuardPolicy(registry, workspace), context, Confirmer { name, args ->
                confirmations.add(name to args); name == "web_search"
            }).run(history).toList()
            assertTrue(events.last() is AgentEvent.Done)
            assertEquals(listOf("web_search", "web_fetch"), confirmations.map { it.first })
            assertEquals("exact query", confirmations.first().second.getValue("query").jsonPrimitive.content)
            assertEquals(1, fetches)
            assertEquals(listOf(1), context.web.sources.map { it.number })
            val first = LlmJson.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
            assertEquals("auto", first.getValue("tool_choice").jsonPrimitive.content)
            assertEquals(2, first.getValue("tools").jsonArray.size)
            assertFalse(first.getValue("messages").jsonArray[1].jsonObject.containsKey("reasoning_content"))
            val second = LlmJson.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject.getValue("messages").jsonArray
            assertEquals("Find evidence", second[3].jsonObject.getValue("reasoning_content").jsonPrimitive.content)
            assertEquals("search1", second.last().jsonObject.getValue("tool_call_id").jsonPrimitive.content)
            val statuses = events.filterIsInstance<AgentEvent.StepsUpdated>().last().steps.filterIsInstance<TurnStep.ToolCall>().map { it.status }
            assertEquals(listOf(ToolStatus.DONE, ToolStatus.DONE, ToolStatus.DENIED), statuses)
            assertEquals("Evidence [1].", history.last().content)
        }
    }

    @Test fun chatWireOmitsToolsAndToolChoice() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(response(chunk(StreamDelta(content = "Hello"), "stop")))
            val client = OpenAiCompatibleClient(server.url("/").toString(), "deepseek-chat", CustomEndpointAuth(""), ClientIdentity("test", "en", 0))
            client.stream(listOf(ChatMessage("user", "hello")), emptyList()).toList()
            val body = LlmJson.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
            assertFalse(body.containsKey("tools"))
            assertFalse(body.containsKey("tool_choice"))
        }
    }

    @Test fun newRunResetsSearchTrustEvenIfContextIsReused() = runTest {
        val workspace = WorkspacePath(temporary.newFolder())
        val context = ToolContext(workspace)
        context.web.registerSearchResult(WebSearchResult("Old", "https://example.org/old", ""))
        val fetch = WebFetchTool { fail("Denied fetch must not run"); "" }
        val registry = ToolRegistry(listOf(fetch))
        var confirmed = false
        var request = 0
        val client = object : LlmClient {
            override fun stream(messages: List<ChatMessage>, tools: List<ToolSpec>) = flowOf(
                if (request++ == 0) call("web_fetch", """{"url":"https://example.org/old"}""")
                else chunk(StreamDelta(content = "Denied")))
        }
        AgentLoop(client, registry, GuardPolicy(registry, workspace), context, Confirmer { _, _ -> confirmed = true; false })
            .run(mutableListOf(ChatMessage("user", "new question"))).toList()
        assertTrue(confirmed)
        assertTrue(context.web.sources.isEmpty())
        assertTrue(context.web.searchHosts.isEmpty())
    }

    @Test fun cancellationWhileConfirmingNeverRunsTheTool() = runTest {
        val workspace = WorkspacePath(temporary.newFolder())
        val entered = CompletableDeferred<Unit>()
        val search = WebSearchTool(fetchPage = { fail("Cancelled confirmation must not search"); "" })
        val registry = ToolRegistry(listOf(search))
        val client = object : LlmClient {
            override fun stream(messages: List<ChatMessage>, tools: List<ToolSpec>) = flowOf(call("web_search", """{"query":"test"}"""))
        }
        val job = launch {
            AgentLoop(client, registry, GuardPolicy(registry, workspace), ToolContext(workspace), Confirmer { _, _ ->
                entered.complete(Unit); awaitCancellation()
            }).run(mutableListOf(ChatMessage("user", "search"))).collect()
        }
        entered.await()
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
    }

    @Test fun resumeRestoresSourceNumbersAndOnlyCurrentTurnHostTrust() = runTest {
        val workspace = WorkspacePath(temporary.newFolder())
        val registry = ToolRegistry(listOf(WebFetchTool { "<p>More evidence</p>" }))
        val context = ToolContext(workspace)
        val checkpoint = RunCheckpoint("run", messages = listOf(ChatMessage("user", "research")),
            sources = listOf(WebSource(1, "Report", "https://example.org/report")), searchHosts = setOf("example.org"))
        var requests = 0
        val client = object : LlmClient {
            override fun stream(messages: List<ChatMessage>, tools: List<ToolSpec>) = flowOf(
                if (requests++ == 0) call("web_fetch", """{"url":"https://example.org/more"}""")
                else chunk(StreamDelta(content = "Answer [2]")))
        }
        val events = AgentLoop(client, registry, GuardPolicy(registry, workspace), context,
            Confirmer { _, _ -> fail("A short query-free URL on a restored search host is safe"); false })
            .resume(checkpoint).toList()
        assertTrue(events.last() is AgentEvent.Done)
        assertEquals(listOf(1, 2), context.web.sources.map { it.number })
    }
}
