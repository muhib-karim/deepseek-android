package app.dsandroid.core.llm

import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.JsonObject
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
    private val thinking: Boolean = false,
    private val vision: Boolean = false,
) : LlmClient {
    private val endpoint = (baseUrl.trimEnd('/') + "/chat/completions").toHttpUrl().also {
        require(it.username.isEmpty() && it.password.isEmpty() && it.query == null && it.fragment == null) { "Invalid base URL" }
        require(it.isHttps || auth is CustomEndpointAuth) { "Provider credentials require HTTPS" }
    }
    private val http = client.newBuilder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS).build()
    private val modelsEndpoint = (baseUrl.trimEnd('/') + "/models").toHttpUrl()

    override suspend fun listModels(): List<ModelInfo> = suspendCancellableCoroutine { continuation ->
        val request = Request.Builder().url(modelsEndpoint).header("Accept", "application/json")
        identity.headers().forEach { (name, value) -> request.header(name, value) }
        auth.headers().forEach { (name, value) -> request.header(name, value) }
        val call = http.newBuilder().callTimeout(30, TimeUnit.SECONDS).build().newCall(request.build())
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val models = response.use {
                        if (!it.isSuccessful) throw ProviderHttpException(it.code, it.peekBody(8192).string(), it.header("Retry-After"))
                        val bytes = it.peekBody(2 * 1024 * 1024 + 1L).bytes()
                        require(bytes.size <= 2 * 1024 * 1024) { "Model list is too large" }
                        LlmJson.decodeFromString<ModelsResponse>(String(bytes, Charsets.UTF_8)).data
                            .filter { model -> model.id.isNotBlank() }.distinctBy { model -> model.id }
                    }
                    if (continuation.isActive) continuation.resume(models)
                } catch (error: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
            }
        })
    }

    override fun stream(messages: List<ChatMessage>, tools: List<ToolSpec>): Flow<ChatChunk> = callbackFlow {
        val payload = LlmJson.encodeToJsonElement(ChatRequest(model, HistoryPolicy.forRequest(messages).map { it.forVision(vision) }, tools,
            if (tools.isEmpty()) null else JsonPrimitive("auto"),
            thinking = if (thinking) buildJsonObject { put("type", "enabled") } else null)).jsonObject.toMutableMap()
        if (tools.isEmpty()) payload.remove("tools")
        val requestBody = JsonObject(payload).toString()
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
                        if (!it.isSuccessful) throw ProviderHttpException(it.code, it.peekBody(8192).string(), it.header("Retry-After"))
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
