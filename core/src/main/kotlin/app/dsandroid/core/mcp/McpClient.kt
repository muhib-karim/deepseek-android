package app.dsandroid.core.mcp

import app.dsandroid.core.tools.*
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl

@Serializable data class RemoteToolDefinition(val name: String, val description: String = "", val inputSchema: JsonObject)
@Serializable data class RemoteServer(val id: String, val name: String, val url: String, val tools: List<RemoteToolDefinition> = emptyList())
data class McpReply(val body: JsonObject?, val sessionId: String? = null)
fun interface McpTransport { suspend fun post(body: JsonObject, headers: Map<String, String>): McpReply }

object McpEndpoint {
    fun validate(value: String): String {
        require(value.length <= 2048 && WebFetchUrlPolicy.isAllowed(value)) { "A public HTTPS endpoint is required" }
        val url = value.toHttpUrl()
        require(url.fragment == null && url.query == null) { "Endpoint queries and fragments are not supported" }
        return url.toString()
    }
}

/** Streamable HTTP: POST JSON-RPC, JSON or bounded SSE replies, no background subscription. */
class McpClient(endpoint: String, private val transport: McpTransport = HttpsMcpTransport(endpoint)) {
    init { McpEndpoint.validate(endpoint) }
    private var nextId = 0
    private var initialized = false
    private var sessionId: String? = null
    private var protocol = "2025-03-26"
    private fun headers() = buildMap {
        put("MCP-Protocol-Version", protocol)
        sessionId?.let { put("Mcp-Session-Id", it) }
    }
    private suspend fun rpc(method: String, params: JsonObject): JsonObject {
        val id = JsonPrimitive(++nextId)
        val reply = transport.post(buildJsonObject {
            put("jsonrpc", "2.0"); put("id", id); put("method", method); put("params", params)
        }, headers())
        reply.sessionId?.let { value ->
            require(value.length in 1..256 && value.all { it.code in 0x21..0x7e })
            sessionId = value
        }
        val body = requireNotNull(reply.body)
        require(body["jsonrpc"] == JsonPrimitive("2.0") && body["id"] == id && body["error"] == null) { "Invalid or failed JSON-RPC response" }
        return requireNotNull(body["result"] as? JsonObject)
    }
    private suspend fun initialize() {
        if (initialized) return
        val result = rpc("initialize", buildJsonObject {
            put("protocolVersion", protocol)
            put("capabilities", JsonObject(emptyMap()))
            put("clientInfo", buildJsonObject { put("name", "DeepSeek-Android-Unofficial"); put("version", "0.2.0") })
        })
        val negotiated = result["protocolVersion"]?.jsonPrimitive?.content
        require(negotiated in setOf("2025-03-26", "2025-06-18")) { "Unsupported MCP protocol" }
        protocol = requireNotNull(negotiated)
        transport.post(buildJsonObject { put("jsonrpc", "2.0"); put("method", "notifications/initialized") }, headers())
        initialized = true
    }
    suspend fun listTools(): List<RemoteToolDefinition> {
        initialize()
        val tools = mutableListOf<RemoteToolDefinition>()
        val cursors = mutableSetOf<String>()
        var cursor: String? = null
        repeat(4) {
            val result = rpc("tools/list", buildJsonObject { cursor?.let { put("cursor", it) } })
            val entries = requireNotNull(result["tools"] as? JsonArray)
            entries.forEach { entry ->
                val obj = entry.jsonObject
                val name = obj["name"]!!.jsonPrimitive.content
                require(name.length in 1..128 && name.none { it.isISOControl() })
                val schema = requireNotNull(obj["inputSchema"] as? JsonObject)
                require(schema["type"] == JsonPrimitive("object") && schema.toString().length <= 32_000)
                tools.add(RemoteToolDefinition(name, obj["description"]?.jsonPrimitive?.content.orEmpty().take(2000), schema))
                require(tools.size <= 64 && tools.count { it.name == name } == 1)
            }
            cursor = (result["nextCursor"] as? JsonPrimitive)?.contentOrNull
            if (cursor.isNullOrBlank()) return tools
            require(cursor!!.length <= 2048 && cursors.add(cursor!!))
        }
        error("Too many tool pages")
    }
    suspend fun call(name: String, arguments: JsonObject): ToolResult {
        require(arguments.toString().length <= 64_000)
        initialize()
        val result = rpc("tools/call", buildJsonObject { put("name", name); put("arguments", arguments) })
        val content = result.toString()
        return ToolResult("Untrusted remote tool result:\n" + content.take(64_000) + if (content.length > 64_000) "\n[Truncated]" else "",
            isError = (result["isError"] as? JsonPrimitive)?.booleanOrNull == true)
    }
}

class RemoteTool(val server: RemoteServer, val definition: RemoteToolDefinition, private val client: McpClient) : Tool {
    override val name = registryName(server.id, definition.name)
    override val description = "Remote tool ${definition.name} at ${server.url}. Every call requires confirmation. " + definition.description.take(2000)
    override val schema = definition.inputSchema
    override val risk = Risk.CONFIRM
    override val kind = ToolKind.NETWORK
    override suspend fun run(args: JsonObject, ctx: ToolContext) = client.call(definition.name, args)
    companion object {
        fun registryName(serverId: String, toolName: String): String {
            fun hash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).take(10).joinToString("") { "%02x".format(it.toInt() and 255) }
            return "mcp_${hash(serverId)}_${hash(toolName)}"
        }
    }
}

object McpWire {
    const val MAX_BYTES = 1024 * 1024
    fun read(input: InputStream, sse: Boolean, id: JsonElement): JsonObject {
        fun parse(text: String) = Json.parseToJsonElement(text) as? JsonObject ?: error("Expected JSON object")
        fun matching(obj: JsonObject) = obj["jsonrpc"] == JsonPrimitive("2.0") && obj["id"] == id
        if (!sse) {
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= MAX_BYTES)
                output.write(buffer, 0, count)
            }
            return parse(output.toString("UTF-8")).also { require(matching(it)) }
        }
        var count = 0
        val line = ByteArrayOutputStream()
        val data = StringBuilder()
        while (true) {
            val byte = input.read()
            if (byte < 0) break
            require(++count <= MAX_BYTES)
            if (byte == 10) {
                val value = line.toString("UTF-8").removeSuffix("\r")
                line.reset()
                if (value.isEmpty() && data.isNotEmpty()) {
                    val obj = parse(data.toString())
                    data.clear()
                    if (matching(obj)) return obj
                    require(obj["id"] == null) { "Unexpected response id" }
                } else if (value.startsWith("data:")) {
                    if (data.isNotEmpty()) data.append('\n')
                    data.append(value.removePrefix("data:").removePrefix(" "))
                }
            } else line.write(byte)
        }
        error("Incomplete event stream")
    }
}
