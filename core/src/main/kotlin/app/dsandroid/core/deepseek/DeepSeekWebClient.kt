package app.dsandroid.core.deepseek

import java.io.File
import java.io.IOException
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

/** What users/current says about a valid session; deliberately not a data class, so debug strings carry no contact data. */
class WebUser(val needBirthday: Boolean, val email: String?, val phone: String?) {
    override fun toString() = "WebUser([redacted])"
}

/** A file uploaded to DeepSeek; it can be referenced by a completion once [status] is SUCCESS. */
data class WebFile(val id: String, val name: String, val bytes: Long?, val status: String)

class DeepSeekWebClient(
    client: OkHttpClient = OkHttpClient(),
    private val origin: HttpUrl = DeepSeekOrigin.URL.toHttpUrl(),
    /** Waits between fetch_files checks while DeepSeek processes an upload (live: 3-8 seconds in total). */
    private val filePollMillis: Long = 1_000,
    private val fileWaitMillis: Long = 90_000,
) {
    companion object {
        const val PAGE_SIZE = 50
        const val MAX_PAGES = 6
        private val WAITING_FILE_STATES = setOf("PENDING", "PARSING", "PROCESSING", "UPLOADING", "WAITING")
    }
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

    /** Pin or unpin a chat on DeepSeek (POST chat_session/update_pinned), so every device lists it the same way. */
    suspend fun setPinned(credentials: WebCredentials, sessionId: String, pinned: Boolean) {
        postJson(credentials, "/api/v0/chat_session/update_pinned", buildJsonObject {
            put("chat_session_id", sessionId); put("pinned", pinned)
        }, allowEmptyAcknowledgement = true)
    }

    /** Like ("GOOD"), Dislike ("BAD") or neither (null) for one reply (POST chat/message_feedback). */
    suspend fun sendFeedback(credentials: WebCredentials, sessionId: String, messageId: Long, feedback: String?) {
        require(feedback == null || feedback == "GOOD" || feedback == "BAD")
        postJson(credentials, "/api/v0/chat/message_feedback", buildJsonObject {
            put("chat_session_id", sessionId); put("message_id", messageId)
            put("feedback_type", feedback?.let(::JsonPrimitive) ?: JsonNull)
        }, allowEmptyAcknowledgement = true)
    }

    /**
     * Asks DeepSeek whether [credentials] are a valid session (the sign-in gate: nothing unlocks without this answer).
     * Throws [SessionExpired] when DeepSeek rejects the session; reads only the date-of-birth flag and the contact fields
     * DeepSeek shows its owner (the response's own token field is ignored).
     */
    suspend fun currentUser(credentials: WebCredentials): WebUser = withContext(Dispatchers.IO) {
        val data = get(credentials, url("/api/v0/users/current"))
        fun text(key: String) = (data[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() && it.length <= 120 }
        WebUser((data["need_birthday"] as? JsonPrimitive)?.booleanOrNull ?: false, text("email"), text("mobile_number"))
    }

    /** New accounts must confirm a date of birth on the web page before chatting (users/current need_birthday). */
    suspend fun needsBirthday(credentials: WebCredentials): Boolean = currentUser(credentials).needBirthday

    /**
     * DeepSeek's chat list, newest first, read page by page (count 2-100; the next page starts at the last chat's
     * updated_at and pinned state, which it repeats). [complete] in the result is false when more pages remain unread.
     */
    suspend fun fetchSessions(credentials: WebCredentials, maxPages: Int = MAX_PAGES): Pair<List<ServerSession>, Boolean> = withContext(Dispatchers.IO) {
        val sessions = LinkedHashMap<String, ServerSession>()
        var cursor: ServerSession? = null
        repeat(maxPages) {
            val url = url("/api/v0/chat_session/fetch_page").newBuilder().addQueryParameter("count", PAGE_SIZE.toString()).apply {
                cursor?.let { last ->
                    addQueryParameter("lte_cursor.updated_at", last.cursor)
                    addQueryParameter("lte_cursor.pinned", last.pinned.toString())
                }
            }.build()
            val (page, more) = ServerHistory.sessions(get(credentials, url))
            val before = sessions.size
            page.forEach { sessions.putIfAbsent(it.id, it) }
            val last = page.lastOrNull()
            if (!more) return@withContext sessions.values.toList() to true
            // A page that adds nothing (or a chat without a usable cursor) would repeat forever.
            if (sessions.size == before || last == null || last.cursor.isEmpty()) return@withContext sessions.values.toList() to false
            cursor = last
        }
        sessions.values.toList() to false
    }

    /** One chat's messages on its current branch (GET chat/history_messages). */
    suspend fun fetchHistory(credentials: WebCredentials, sessionId: String): ServerChat = withContext(Dispatchers.IO) {
        val url = url("/api/v0/chat/history_messages").newBuilder().addQueryParameter("chat_session_id", sessionId).build()
        val chat = ServerHistory.chat(get(credentials, url))
        if (chat.session.id != sessionId) throw EndpointChanged()
        chat
    }

    /**
     * Uploads one file with its own proof of work (target upload_file), then waits until DeepSeek has processed it
     * (GET file/fetch_files until SUCCESS). A file DeepSeek fails, or does not finish within the wait, is a [WebFileFailed]
     * naming that file. Returns the processed file, ready to be listed in ref_file_ids.
     */
    suspend fun uploadFile(credentials: WebCredentials, file: File, name: String, mime: String,
        solve: suspend (PowChallenge) -> Long = { DeepSeekHashV1.solve(it) }): WebFile {
        val pow = proofOfWork(credentials, PowChallenge.UPLOAD_PATH, solve)
        val uploaded = withContext(Dispatchers.IO) {
            val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("file", name, file.asRequestBody(mime.toMediaType())).build()
            val data = exchange(authenticatedRequest(credentials, url(PowChallenge.UPLOAD_PATH)).header("X-DS-PoW-Response", pow).post(body).build()) { response ->
                decodeEnvelope(readBounded(response.body ?: throw EndpointChanged()))
            }
            webFile(data["file"] as? JsonObject ?: data, name)
        }
        return awaitProcessed(credentials, uploaded)
    }

    private suspend fun awaitProcessed(credentials: WebCredentials, file: WebFile): WebFile {
        var current = file
        val deadline = System.currentTimeMillis() + fileWaitMillis
        while (true) {
            when (current.status) {
                "SUCCESS" -> return current
                in WAITING_FILE_STATES -> Unit
                else -> throw WebFileFailed(file.name)
            }
            if (System.currentTimeMillis() >= deadline) throw WebFileFailed(file.name, stillProcessing = true)
            delay(filePollMillis)
            current = fetchFile(credentials, file)
        }
    }

    private suspend fun fetchFile(credentials: WebCredentials, file: WebFile): WebFile = withContext(Dispatchers.IO) {
        val url = url("/api/v0/file/fetch_files").newBuilder().addQueryParameter("file_ids", file.id).build()
        val files = get(credentials, url)["files"] as? JsonArray ?: throw EndpointChanged()
        val match = files.mapNotNull { it as? JsonObject }.firstOrNull { (it["id"] as? JsonPrimitive)?.contentOrNull == file.id } ?: throw EndpointChanged()
        webFile(match, file.name)
    }

    private fun webFile(value: JsonObject, name: String): WebFile {
        val id = requiredId(value["id"])
        val status = (value["status"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.take(40) ?: "SUCCESS"
        val bytes = (value["file_size"] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull?.takeIf { it >= 0 }
        return WebFile(id, name, bytes, status)
    }

    /** Creates a challenge for [targetPath], solves it and returns the X-DS-PoW-Response header for that request. */
    suspend fun proofOfWork(credentials: WebCredentials, targetPath: String, solve: suspend (PowChallenge) -> Long): String {
        val challenge = PowChallenge.parse(postJson(credentials, "/api/v0/chat/create_pow_challenge", buildJsonObject {
            put("target_path", targetPath)
        }).get("challenge") as? JsonObject ?: throw UpdateRequired())
        challenge.requireUsable(System.currentTimeMillis())
        val answer = solve(challenge)
        // A challenge that expired while solving is stale, not a changed service: a fresh one passes.
        if (System.currentTimeMillis() >= challenge.expireAt) throw WebPowRejected()
        return challenge.header(answer, targetPath)
    }

    fun complete(credentials: WebCredentials, input: CompletionInput, solve: suspend (PowChallenge) -> Long): Flow<WebReply> = flow {
        val pow = proofOfWork(credentials, PowChallenge.COMPLETION_PATH, solve)
        val payload = buildJsonObject {
            put("chat_session_id", input.sessionId)
            put("parent_message_id", input.parentMessageId?.let(::JsonPrimitive) ?: JsonNull)
            put("model_type", "default"); put("prompt", input.prompt)
            put("ref_file_ids", JsonArray(input.refFileIds.map(::JsonPrimitive)))
            put("thinking_enabled", input.thinking); put("search_enabled", input.search)
            put("action", JsonNull); put("preempt", false)
        }
        val request = request(credentials, PowChallenge.COMPLETION_PATH, payload)
            .header("Accept", "text/event-stream").header("X-DS-PoW-Response", pow).build()
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
                // Events already received after FINISHED (the session time, the generated title) are read without waiting.
                while (terminal && reader.ready()) {
                    val count = reader.read(buffer)
                    if (count < 0) break
                    stream.feed(String(buffer, 0, count))
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

    private suspend fun get(credentials: WebCredentials, url: HttpUrl): JsonObject = exchange(authenticatedRequest(credentials, url).get().build()) { response ->
        decodeEnvelope(readBounded(response.body ?: throw EndpointChanged()))
    }

    private fun url(path: String): HttpUrl = origin.resolve(path) ?: throw EndpointChanged()

    private fun request(credentials: WebCredentials, path: String, value: JsonObject): Request.Builder =
        authenticatedRequest(credentials, url(path))
            .post(value.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))

    private fun authenticatedRequest(credentials: WebCredentials, url: HttpUrl): Request.Builder {
        credentials.requireUsable(System.currentTimeMillis())
        return Request.Builder().url(url)
            .header("Authorization", "Bearer ${credentials.token.bearer()}")
            .apply { if (credentials.cookies.isNotBlank()) header("Cookie", credentials.cookies) }
            .header("Origin", DeepSeekOrigin.URL).header("Referer", "${DeepSeekOrigin.URL}/")
            .header("x-client-platform", "web").header("x-client-version", "2.5.0")
            .header("x-client-locale", "en_US").header("x-client-bundle-id", "com.deepseek.chat")
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
                    in 500..599 -> throw WebServiceBusy()
                    else -> throw UpdateRequired()
                }
            }
        } catch (error: IOException) {
            currentCoroutineContext().ensureActive()
            // Our own cancel() closed the call: that is cancellation, never a connection failure.
            if (call.isCanceled()) throw CancellationException("DeepSeek request cancelled")
            // Timeouts, DNS, TLS and bodies cut off in transit (EOF, unexpected end of stream) are the connection.
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
        } catch (error: Exception) {
            if (error is WebFailure) throw error
            throw UpdateRequired()
        }
    }
}
