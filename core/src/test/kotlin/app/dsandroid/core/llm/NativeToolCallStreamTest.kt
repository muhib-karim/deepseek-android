package app.dsandroid.core.llm

import app.dsandroid.core.agent.AgentEvent
import app.dsandroid.core.agent.AgentLoop
import app.dsandroid.core.agent.Confirmer
import app.dsandroid.core.agent.ToolStatus
import app.dsandroid.core.agent.TurnStep
import app.dsandroid.core.guard.GuardPolicy
import app.dsandroid.core.guard.WorkspacePath
import app.dsandroid.core.tools.*
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Native function calling on the API-key client: tools array out, streamed tool_calls in, role "tool" results back. */
class NativeToolCallStreamTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun sse(vararg events: String) = MockResponse().setHeader("Content-Type", "text/event-stream")
        .setBody(events.joinToString("") { "data: $it\n\n" } + "data: [DONE]\n\n")

    private fun client(server: MockWebServer) = OpenAiCompatibleClient(server.url("/").toString(), "deepseek-chat",
        CustomEndpointAuth(""), ClientIdentity("0.6.0", "en", 0))

    private val pythonSpec = ToolSpec(function = FunctionSpec("run_python", "Run Python", objectSchema(mapOf(
        "code" to stringSchema("Code"), "timeout_s" to buildJsonObject { put("type", "integer") }), listOf("code"))))

    @Test fun interleavedToolCallDeltasAssembleByIndex() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.enqueue(sse(
                """{"choices":[{"index":0,"delta":{"role":"assistant","content":null,"reasoning_content":"Plan."}}]}""",
                """{"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call_a","type":"function","function":{"name":"run_python","arguments":""}}]}}]}""",
                """{"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\"code\": \"pri"}}]}}]}""",
                """{"choices":[{"index":0,"delta":{"tool_calls":[{"index":1,"id":"call_b","type":"function","function":{"name":"read","arguments":"{\"file_path\":"}}]}}]}""",
                """{"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"nt(2 + 2)\", \"timeout_s\": 5}"}}]}}]}""",
                """{"choices":[{"index":0,"delta":{"tool_calls":[{"index":1,"function":{"arguments":" \"notes.txt\"}"}}]}}]}""",
                """{"choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}]}""",
            ).throttleBody(64, 1, TimeUnit.MILLISECONDS))
            val accumulator = StreamAccumulator()
            client(server).stream(listOf(ChatMessage("user", "Compute")), listOf(pythonSpec)).toList().forEach(accumulator::accept)
            val message = accumulator.message()
            assertEquals("Plan.", message.reasoningContent)
            assertNull(message.content)
            val calls = message.toolCalls!!
            assertEquals(listOf("call_a", "call_b"), calls.map { it.id })
            assertEquals(listOf("run_python", "read"), calls.map { it.function.name })
            val python = Json.parseToJsonElement(calls[0].function.arguments).jsonObject
            assertEquals("print(2 + 2)", python["code"]!!.jsonPrimitive.content)
            assertEquals(5, python["timeout_s"]!!.jsonPrimitive.int)
            assertEquals("notes.txt", Json.parseToJsonElement(calls[1].function.arguments).jsonObject["file_path"]!!.jsonPrimitive.content)
        }
    }

    @Test fun requestCarriesToolsAndToolResultMessages() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.enqueue(sse("""{"choices":[{"index":0,"delta":{"content":"4"},"finish_reason":"stop"}]}"""))
            val history = listOf(
                ChatMessage("user", "Compute"),
                ChatMessage("assistant", null, toolCalls = listOf(ToolCall("call_a", function = FunctionCall("run_python", """{"code":"print(2+2)"}""")))),
                ChatMessage("tool", """{"content":"4\n","isError":false,"artifacts":[]}""", toolCallId = "call_a"),
            )
            client(server).stream(history, listOf(pythonSpec)).toList()
            val body = Json.parseToJsonElement(server.takeRequest(2, TimeUnit.SECONDS)!!.body.readUtf8()).jsonObject
            val tool = body["tools"]!!.jsonArray.single().jsonObject
            assertEquals("function", tool["type"]!!.jsonPrimitive.content)
            assertEquals("run_python", tool["function"]!!.jsonObject["name"]!!.jsonPrimitive.content)
            assertEquals(listOf("code"), tool["function"]!!.jsonObject["parameters"]!!.jsonObject["required"]!!.jsonArray.map { it.jsonPrimitive.content })
            assertEquals("auto", body["tool_choice"]!!.jsonPrimitive.content)
            val messages = body["messages"]!!.jsonArray.map { it.jsonObject }
            val call = messages[1]["tool_calls"]!!.jsonArray.single().jsonObject
            assertEquals("call_a", call["id"]!!.jsonPrimitive.content)
            assertEquals("function", call["type"]!!.jsonPrimitive.content)
            assertEquals("run_python", call["function"]!!.jsonObject["name"]!!.jsonPrimitive.content)
            assertEquals("tool", messages[2]["role"]!!.jsonPrimitive.content)
            assertEquals("call_a", messages[2]["tool_call_id"]!!.jsonPrimitive.content)
        }
    }

    @Test fun agentLoopRoundTripsANativeToolCall() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.enqueue(sse(
                """{"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"echo","arguments":"{\"text\":"}}]}}]}""",
                """{"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"\"native\"}"}}]},"finish_reason":"tool_calls"}]}""",
            ))
            server.enqueue(sse("""{"choices":[{"index":0,"delta":{"content":"Echoed."},"finish_reason":"stop"}]}"""))
            val echo = object : Tool {
                override val name = "echo"
                override val description = "Echo"
                override val schema = objectSchema(mapOf("text" to stringSchema("Text")))
                override val risk = Risk.SAFE
                override val kind = ToolKind.READ
                override suspend fun run(args: JsonObject, ctx: ToolContext) = ToolResult("echo: ${args.stringArg("text")}")
            }
            val workspace = WorkspacePath(temporary.newFolder())
            val registry = ToolRegistry(listOf(echo))
            val history = mutableListOf(ChatMessage("user", "Echo natively"))
            val events = AgentLoop(client(server), registry, GuardPolicy(registry, workspace), ToolContext(workspace), Confirmer { _, _ -> false })
                .run(history).toList()
            assertTrue(events.last() is AgentEvent.Done)
            val call = events.filterIsInstance<AgentEvent.StepsUpdated>().last().steps.filterIsInstance<TurnStep.ToolCall>().single()
            assertEquals(ToolStatus.DONE, call.status)
            server.takeRequest(2, TimeUnit.SECONDS)
            val second = Json.parseToJsonElement(server.takeRequest(2, TimeUnit.SECONDS)!!.body.readUtf8()).jsonObject
            val toolMessage = second["messages"]!!.jsonArray.last().jsonObject
            assertEquals("tool", toolMessage["role"]!!.jsonPrimitive.content)
            assertEquals("call_1", toolMessage["tool_call_id"]!!.jsonPrimitive.content)
            val result = Json.parseToJsonElement(toolMessage["content"]!!.jsonPrimitive.content).jsonObject
            assertEquals("echo: native", result["content"]!!.jsonPrimitive.content)
            assertEquals("Echoed.", history.last().content)
        }
    }
}
