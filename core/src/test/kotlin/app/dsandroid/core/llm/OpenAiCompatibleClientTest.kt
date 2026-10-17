package app.dsandroid.core.llm

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelAndJoin
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class OpenAiCompatibleClientTest {
    @Test fun streamsToolCallAndSendsExpectedWireContract() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
                "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"c1\",\"type\":\"function\",\"function\":{\"name\":\"create_markdown\",\"arguments\":\"{\"}}]}}]}\n\n" +
                "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"}\"}}]},\"finish_reason\":\"tool_calls\"}]}\n\n" +
                "data: [DONE]\n\n").throttleBody(13, 1, TimeUnit.MILLISECONDS))
            val client = OpenAiCompatibleClient(server.url("/v1").toString(), "deepseek-chat", CustomEndpointAuth("test-value"), ClientIdentity("0.1.0", "en", 0))
            val chunks = client.stream(listOf(ChatMessage("user", "Write")), listOf(ToolSpec(function = FunctionSpec("create_markdown", "Write", JsonObject(emptyMap()))))).toList()
            val accumulator = StreamAccumulator()
            chunks.forEach(accumulator::accept)
            assertEquals("{}", accumulator.message().toolCalls!!.single().function.arguments)
            val request = server.takeRequest(2, TimeUnit.SECONDS)!!
            assertEquals("/v1/chat/completions", request.path)
            assertEquals("Bearer test-value", request.getHeader("Authorization"))
            assertEquals("DeepSeek-Android-Unofficial/0.1.0 (Android)", request.getHeader("User-Agent"))
            val body = LlmJson.parseToJsonElement(request.body.readUtf8()).jsonObject
            assertTrue(body["stream"]!!.jsonPrimitive.boolean)
            assertEquals("auto", body["tool_choice"]!!.jsonPrimitive.content)
        }
    }
    @Test fun cancellationStopsAWaitingStream() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE))
            val client = OpenAiCompatibleClient(server.url("/").toString(), "model", CustomEndpointAuth(""), ClientIdentity("test", "en", 0))
            val job = launch(kotlinx.coroutines.Dispatchers.Default) { client.stream(emptyList(), emptyList()).toList() }
            assertNotNull(server.takeRequest(3, TimeUnit.SECONDS))
            kotlinx.coroutines.withTimeout(3000) { job.cancelAndJoin() }
            assertTrue(job.isCancelled)
        }
    }
    @Test fun refusesRedirectsAndPrematureEof() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", server.url("/other")))
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody("data: {\"choices\":[{\"delta\":{\"content\":\"partial\"}}]}\n\n"))
            val client = OpenAiCompatibleClient(server.url("/").toString(), "model", CustomEndpointAuth(""), ClientIdentity("test", "en", 0))
            repeat(2) {
                try { client.stream(emptyList(), emptyList()).toList(); fail("Expected stream failure") }
                catch (_: java.io.IOException) { }
            }
            assertEquals(2, server.requestCount)
        }
    }
}
