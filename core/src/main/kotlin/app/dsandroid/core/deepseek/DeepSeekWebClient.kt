package app.dsandroid.core.deepseek

import java.io.EOFException
import java.io.File
import java.io.IOException
import java.net.ProtocolException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.RequestBody.Companion.asRequestBody

/** Input contains no authentication material; never use it as a diagnostic payload. */
data class CompletionInput(
    val sessionId: String, val parentMessageId: Long?, val prompt: String,
    val thinking: Boolean, val search: Boolean, val refFileIds: List<String> = emptyList(),
)

class DeepSeekWebClient(
    client: OkHttpClient = OkHttpClient(),
    private val origin: HttpUrl = DeepSeekOrigin.URL.toHttpUrl(),
) {
    private val http = client.newBuilder().followRedirects(false).followSslRedirects(false)
        .cookieJar(CookieJar.NO_COOKIES).retryOnConnectionFailure(false)
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(90, TimeUnit.SECONDS).callTimeout(180, TimeUnit.SECONDS).build()

    suspend fun createSession(credentials: WebCredentials): String = withContext(Dispatchers.IO) {
        val data = postJson(credentials, "/api/v0/chat_session/create", buildJsonObject {})
        requiredId(data["id"] ?: (data["chat_session"] as? JsonObject)?.get("id"))
    }

    suspend fun renameSession(credentials: WebCredentials, sessionId: String, title: String) {
        postJson(credentials, "/api/v0/chat_session/update_title", buildJsonObject {
            put("chat_session_id", sessionId); put("title", title)
        }, allowEmptyAcknowledgement = true)
    }

    suspend fun deleteSession(credentials: WebCredentials, sessionId: String) {
        postJson(credentials, "/api/v0/chat_session/delete", buildJsonObject { put("chat_session_id", sessionId) }, allowEmptyAcknowledgement = true)
    }

    suspend fun uploadFile(credentials: WebCredentials, file: File, name: String, mime: String): String = withContext(Dispatchers.IO) {
        val mediaType = mime.toMediaType()
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", name, file.asRequestBody(mediaType)).build()
        val data = exchange(authenticatedRequest(credentials, "/api/v0/file/upload_file").post(body).build()) { response ->
            decodeEnvelope(readBounded(response.body ?: throw EndpointChanged()))
        }
        requiredId(data["id"] ?: (data["file"] as? JsonObject)?.get("id"))
    }

    fun complete(credentials: WebCredentials, input: CompletionInput, solve: suspend (PowChallenge) -> Long): Flow<WebReply> = flow {
        val challenge = PowChallenge.parse(postJson(credentials, "/api/v0/chat/create_pow_challenge", buildJsonObject {
            put("target_path", PowChallenge.COMPLETION_PATH)
        }).get("challenge") as? JsonObject ?: throw UpdateRequired())
        challenge.requireUsable(System.currentTimeMillis())
        val answer = solve(challenge)
        challenge.requireUsable(System.currentTimeMillis())
        val payload = buildJsonObject {
            put("chat_session_id", input.sessionId)
            put("parent_message_id", input.parentMessageId?.let(::JsonPrimitive) ?: JsonNull)
            put("model_type", "default"); put("prompt", input.prompt)
            put("ref_file_ids", JsonArray(input.refFileIds.map(::JsonPrimitive)))
            put("thinking_enabled", input.thinking); put("search_enabled", input.search)
            put("action", JsonNull); put("preempt", false)
        }
        val request = request(credentials, PowChallenge.COMPLETION_PATH, payload)
            .header("Accept", "text/event-stream").header("X-DS-PoW-Response", challenge.header(answer)).build()
        exchange(request) { response ->
            val body = response.body ?: throw UpdateRequired()
            if (body.contentType()?.let { "${it.type}/${it.subtype}" } != "text/event-stream") {
                decodeEnvelope(readBounded(body)); throw UpdateRequired()
            }
            val stream = DeepSeekStream()
            body.charStream().use { reader ->
                val buffer = CharArray(4096)
                var terminal = false
                while (!terminal) {
                    currentCoroutineContext().ensureActive()
                    val count = reader.read(buffer)
                    if (count < 0) break
                    stream.feed(String(buffer, 0, count)).forEach { reply -> emit(reply); terminal = reply.finished }
                }
            }
            val final = stream.finish()
            if (final.responseMessageId == null || final.response.isBlank()) throw UpdateRequired()
            emit(final)
        }
    }.flowOn(Dispatchers.IO)

    private suspend fun postJson(
        credentials: WebCredentials, path: String, value: JsonObject, allowEmptyAcknowledgement: Boolean = false,
    ): JsonObject = withContext(Dispatchers.IO) {
        exchange(request(credentials, path, value).build()) { response ->
            decodeEnvelope(readBounded(response.body ?: throw EndpointChanged()), allowEmptyAcknowledgement)
        }
    }

    private fun request(credentials: WebCredentials, path: String, value: JsonObject): Request.Builder =
        authenticatedRequest(credentials, path)
            .post(value.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))

    private fun authenticatedRequest(credentials: WebCredentials, path: String): Request.Builder {
        credentials.requireUsable(System.currentTimeMillis())
        return Request.Builder().url(origin.resolve(path) ?: throw EndpointChanged())
            .header("Authorization", "Bearer ${credentials.token.bearer()}").header("Cookie", credentials.cookies)
            .header("Origin", DeepSeekOrigin.URL).header("Referer", "${DeepSeekOrigin.URL}/")
            .header("x-client-platform", "web").header("x-client-version", "2.2.0")
            .header("x-client-locale", "zh_CN").header("x-client-bundle-id", "com.deepseek.chat")
    }
    private suspend fun <T> exchange(request: Request, read: suspend (Response) -> T): T = coroutineScope {
        val call = http.newCall(request)
        // Cancellation closes a blocking body read as well as the initial request.
        val cancellation = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { call.cancel() }
        }
        try {
            call.execute().use { response ->
                when (response.code) {
                    401 -> throw SessionExpired()
                    429 -> throw WebRateLimited()
                    in 200..299 -> read(response)
                    else -> throw UpdateRequired()
                }
            }
        } catch (error: IOException) {
            currentCoroutineContext().ensureActive()
            // Our own cancel() closed the call: that is cancellation, never a connection failure.
            if (call.isCanceled()) throw CancellationException("DeepSeek request cancelled")
            if (error is ProtocolException || error is EOFException) throw EndpointChanged()
            throw WebConnectionFailed()
        } finally { cancellation.cancel() }
    }
    private fun readBounded(body: ResponseBody): String {
        val source = body.source()
        if (source.request(1_048_577L)) throw UpdateRequired()
        return source.readUtf8()
    }
    private fun requiredId(value: JsonElement?): String =
        (value as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?.takeIf { it.isNotBlank() && it.length <= 256 } ?: throw EndpointChanged()

    private fun requiredCode(value: JsonElement?): Int =
        (value as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull ?: throw EndpointChanged()

    private fun decodeEnvelope(body: String, allowEmptyAcknowledgement: Boolean = false): JsonObject {
        try {
            val root = Json.parseToJsonElement(body).jsonObject
            checkWebCode(requiredCode(root["code"]))
            val data = root["data"] as? JsonObject ?: throw EndpointChanged()
            checkWebCode(requiredCode(data["biz_code"]))
            if (allowEmptyAcknowledgement && data["biz_data"] == JsonNull) return buildJsonObject {}
            return data["biz_data"] as? JsonObject ?: throw EndpointChanged()
        } catch (error: SessionExpired) { throw error }
        catch (error: WebRateLimited) { throw error }
        catch (_: Exception) { throw UpdateRequired() }
    }
}
