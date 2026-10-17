package app.dsandroid.core.mcp

import app.dsandroid.core.guard.*
import app.dsandroid.core.tools.*
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class McpClientTest {
    @Test fun rejectsInsecurePrivateCredentialAndFragmentEndpoints() {
        listOf("http://example.org/mcp", "https://localhost/mcp", "https://127.0.0.1/mcp", "https://user:pass@example.org/mcp", "https://example.org/mcp#x").forEach {
            assertThrows(IllegalArgumentException::class.java) { McpEndpoint.validate(it) }
        }
        assertEquals("https://example.org/mcp", McpEndpoint.validate("https://example.org/mcp"))
    }

    @Test fun initializationPaginationAndToolCallUseOnlyToolArguments() = runTest {
        val methods = mutableListOf<String>()
        val requests = mutableListOf<JsonObject>()
        val transport = McpTransport { body, headers ->
            requests.add(body)
            val method = body["method"]!!.jsonPrimitive.content
            methods.add(method)
            val result = when (method) {
                "initialize" -> """{"protocolVersion":"2025-03-26","capabilities":{"tools":{}}}"""
                "notifications/initialized" -> return@McpTransport McpReply(null)
                "tools/list" -> {
                    assertEquals("session", headers["Mcp-Session-Id"])
                    if (body["params"]!!.jsonObject["cursor"] == null) """{"tools":[{"name":"lookup","description":"Look up","inputSchema":{"type":"object"}}],"nextCursor":"next"}"""
                    else """{"tools":[{"name":"write","inputSchema":{"type":"object"}}]}"""
                }
                else -> """{"content":[{"type":"text","text":"OK"}],"isError":false}"""
            }
            McpReply(buildJsonObject { put("jsonrpc", "2.0"); put("id", body["id"]!!); put("result", Json.parseToJsonElement(result)) }, "session")
        }
        val client = McpClient("https://example.org/mcp", transport)
        assertEquals(listOf("lookup", "write"), client.listTools().map { it.name })
        val args = buildJsonObject { put("query", "hello") }
        assertFalse(client.call("lookup", args).isError)
        assertEquals(listOf("initialize", "notifications/initialized", "tools/list", "tools/list", "tools/call"), methods)
        val params = requests.last()["params"]!!.jsonObject
        assertEquals(args, params["arguments"])
        assertEquals(setOf("name", "arguments"), params.keys)
    }

    @Test fun streamStopsAtMatchingResponseAndRejectsMismatchedOrOversizedJson() {
        val stream = ": keepalive\n\ndata: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/message\"}\n\ndata: {\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}\n\n"
        assertEquals(JsonPrimitive(1), McpWire.read(stream.byteInputStream(), true, JsonPrimitive(1))["id"])
        assertThrows(IllegalArgumentException::class.java) { McpWire.read("""{"jsonrpc":"2.0","id":2,"result":{}}""".byteInputStream(), false, JsonPrimitive(1)) }
        assertThrows(IllegalArgumentException::class.java) { McpWire.read("x".repeat(McpWire.MAX_BYTES + 1).byteInputStream(), false, JsonPrimitive(1)) }
    }

    @Test fun remoteNamesCannotShadowBuiltinsAndDisabledToolsAreDenied() {
        val descriptor = RemoteToolDefinition("web_fetch", "Remote", buildJsonObject { put("type", "object") })
        val first = RemoteServer("one", "Server", "https://example.org/mcp", listOf(descriptor))
        val second = first.copy(id = "two")
        val a = RemoteTool(first, descriptor, McpClient(first.url))
        val b = RemoteTool(second, descriptor, McpClient(second.url))
        assertNotEquals("web_fetch", a.name)
        assertNotEquals(a.name, b.name)
        assertTrue(a.name.length <= 64)
        val dir = Files.createTempDirectory("mcp-guard").toFile()
        try {
            val workspace = WorkspacePath(dir)
            assertEquals(Decision.CONFIRM, GuardPolicy(ToolRegistry(listOf(a)), workspace).decide(a.name, JsonObject(emptyMap()), GuardSettings()))
            val disabled = ToolRegistry(listOf(a), setOf(a.name))
            assertTrue(disabled.specs().isEmpty())
            assertEquals(Decision.DENY, GuardPolicy(disabled, workspace).decide(a.name, JsonObject(emptyMap()), GuardSettings()))
        } finally { dir.deleteRecursively() }
    }
}
