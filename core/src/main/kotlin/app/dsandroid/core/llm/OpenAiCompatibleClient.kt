package app.dsandroid.core.llm

import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

class OpenAiCompatibleClient(
    baseUrl: String,
    private val model: String,
    private val auth: AuthProvider,
    private val identity: ClientIdentity,
    client: OkHttpClient = OkHttpClient(),
) : LlmClient {
    private val endpoint = (baseUrl.trimEnd('/') + "/chat/completions").toHttpUrl().also {
        require(it.username.isEmpty() && it.password.isEmpty() && it.query == null && it.fragment == null) { "Invalid base URL" }
        require(it.isHttps || auth is CustomEndpointAuth) { "Provider credentials require HTTPS" }
    }
    private val http = client.newBuilder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(5, TimeUnit.MINUTES).build()

    override fun stream(messages: List<ChatMessage>, tools: List<ToolSpec>): Flow<ChatChunk> = callbackFlow {
        val requestBody = LlmJson.encodeToString(ChatRequest(model, messages, tools,
            if (tools.isEmpty()) null else JsonPrimitive("auto")))
        val request = Request.Builder().url(endpoint)
            .header("Accept", "text/event-stream")
            .post(requestBody.toRequestBody("application/json; charset=utf-8".toMediaType()))
        identity.headers().forEach { (name, value) -> request.header(name, value) }
        auth.headers().forEach { (name, value) -> request.header(name, value) }
        val activeResponse = AtomicReference<Response?>(null)
        val call = http.newCall(request.build())
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { close(e) }
            override fun onResponse(call: Call, response: Response) {
                activeResponse.set(response)
                try {
                    response.use {
                        if (!it.isSuccessful) throw IOException("Provider returned HTTP ${it.code}")
                        val body = it.body ?: throw IOException("Empty provider response")
                        if (body.contentType()?.subtype != "event-stream") throw IOException("Expected an SSE response")
                        val parser = SseParser()
                        var done = false
                        fun publish(events: List<String>) {
                            for (event in events) {
                                if (event.trim() == "[DONE]") { done = true; break }
                                if (event.isBlank()) continue
                                val chunk = LlmJson.decodeFromString<ChatChunk>(event)
                                if (trySendBlocking(chunk).isFailure) throw IOException("Stream cancelled")
                            }
                        }
                        body.charStream().use { reader ->
                            val buffer = CharArray(4096)
                            while (!done) {
                                val count = reader.read(buffer)
                                if (count == -1) { publish(parser.finish()); break }
                                publish(parser.feed(String(buffer, 0, count)))
                            }
                        }
                        if (!done) throw IOException("Provider stream ended before [DONE]")
                    }
                    close()
                } catch (error: Exception) {
                    close(error)
                } finally {
                    activeResponse.set(null)
                }
            }
        })
        awaitClose { call.cancel(); activeResponse.getAndSet(null)?.close() }
    }
}
