package app.dsandroid.core.mcp

import app.dsandroid.core.tools.WebFetchUrlPolicy
import java.io.IOException
import java.net.InetAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.JsonObject
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

/** No provider credentials, cookies, redirects, proxies or private DNS destinations. */
class HttpsMcpTransport(endpoint: String) : McpTransport {
    private val url = McpEndpoint.validate(endpoint)
    override suspend fun post(body: JsonObject, headers: Map<String, String>): McpReply {
        val payload = body.toString()
        require(payload.length <= 128_000)
        val request = Request.Builder().url(url)
            .header("Accept", "application/json, text/event-stream")
            .header("User-Agent", "DeepSeek-Android-Unofficial/0.2 (Android)")
            .post(payload.toRequestBody("application/json".toMediaType()))
        headers.forEach { (name, value) ->
            require(name in setOf("MCP-Protocol-Version", "Mcp-Session-Id"))
            request.header(name, value)
        }
        val call = http.newCall(request.build())
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { continuation.resumeWithException(e) }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        val reply = response.use {
                            require(it.isSuccessful) { "Tool server request failed; redirects are refused" }
                            val id = body["id"]
                            if (id == null) return@use McpReply(null, it.header("Mcp-Session-Id"))
                            val responseBody = requireNotNull(it.body)
                            require(responseBody.contentLength() <= McpWire.MAX_BYTES)
                            val type = responseBody.contentType()
                            val sse = type?.type == "text" && type.subtype == "event-stream"
                            require(sse || (type?.type == "application" && type.subtype == "json"))
                            McpReply(responseBody.byteStream().use { stream -> McpWire.read(stream, sse, id) }, it.header("Mcp-Session-Id"))
                        }
                        continuation.resume(reply)
                    } catch (error: Exception) { continuation.resumeWithException(error) }
                }
            })
        }
    }
    companion object {
        private val http = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
            .retryOnConnectionFailure(false).proxy(Proxy.NO_PROXY)
            .dns(object : Dns {
                override fun lookup(hostname: String): List<InetAddress> {
                    val addresses = Dns.SYSTEM.lookup(hostname)
                    if (addresses.isEmpty() || addresses.any { !WebFetchUrlPolicy.isPublicAddress(it) }) throw IOException("Private destination blocked")
                    return addresses
                }
            }).connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS).build()
    }
}
