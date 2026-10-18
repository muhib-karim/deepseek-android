package app.dsandroid.core.deepseek

import kotlinx.serialization.json.*

data class WebReply(
    val thinking: String = "", val response: String = "", val responseMessageId: Long? = null,
    val finished: Boolean = false, val requestMessageId: Long? = null,
)

/** Independently implemented reducer for the web service's SSE fragment patches. */
class DeepSeekStream {
    private companion object {
        val METADATA_EVENTS = setOf("update_session", "title", "close")
        val SHOWN = setOf("THINK", "RESPONSE")
        val FRAGMENT_TYPE = Regex("[A-Z][A-Z_]{0,31}")
        val FRAGMENT_CONTENT = Regex("response/fragments/(-1|[0-9]+)/content")
        val FRAGMENT_FIELD = Regex("response/fragments/(-1|[0-9]+)(/[a-z_]{1,40})?")
    }
    private data class Fragment(val type: String, var text: String)
    private val framing = WebSseFraming()
    private val fragments = mutableListOf<Fragment>()
    private var lastPath = ""
    private var lastOperation = "APPEND"
    private var messageId: Long? = null
    private var requestId: Long? = null
    private var finished = false

    fun feed(chunk: String): List<WebReply> = safely {
        framing.feed(chunk).map { consume(it); snapshot() }
    }
    fun finish(): WebReply = safely {
        framing.finish().forEach(::consume)
        if (!finished) throw EndpointChanged()
        snapshot()
    }
    private fun snapshot() = WebReply(
        fragments.filter { it.type == "THINK" }.joinToString("") { it.text },
        fragments.filter { it.type == "RESPONSE" }.joinToString("") { it.text }, messageId, finished, requestId,
    )
    private fun consume(event: WebSseEvent) {
        if (finished) return
        val data = event.data
        if (data == "[DONE]") { finished = true; return }
        if (data.isBlank()) {
            if (event.name in setOf("finish", "done")) finished = true
            return
        }
        val json = Json.parseToJsonElement(data).jsonObject
        checkCodes(json)
        // Live service metadata events (session timestamps, generated titles, close hints) carry no reply text.
        if (event.name in METADATA_EVENTS) return
        if (json.containsKey("error") || event.name == "error") throw EndpointChanged()
        json["request_message_id"]?.let { requestId = id(it) }
        json["response_message_id"]?.let { messageId = id(it) }
        when {
            event.name == "ready" && json.isEmpty() -> Unit
            event.name in setOf("finish", "done") && json.isEmpty() -> Unit
            "choices" in json -> legacyDelta(json)
            json.keys.any { it in setOf("p", "o", "v") } -> apply(json)
            else -> root(json)
        }
        if (event.name in setOf("finish", "done")) finished = true
        if (fragments.sumOf { it.text.length } > 500_000) throw EndpointChanged()
    }
    private fun apply(patch: JsonObject, prefix: String = "") {
        checkCodes(patch)
        val explicitPath = patch["p"]?.let(::text)
        val path = if (explicitPath == null) lastPath else listOf(prefix, explicitPath.trim('/'))
            .filter { it.isNotEmpty() }.joinToString("/")
        val operation = patch["o"]?.let(::text) ?: lastOperation
        if (explicitPath != null) lastPath = path
        if (operation == "BATCH") {
            val value = patch["v"] as? JsonArray ?: throw EndpointChanged()
            value.forEach { apply(it as? JsonObject ?: throw EndpointChanged(), path) }
            return
        }
        if (operation !in setOf("APPEND", "SET")) throw EndpointChanged()
        lastOperation = operation
        val value = patch["v"] ?: return
        when {
            path.isEmpty() && value is JsonObject -> root(value)
            path == "request_message_id" -> requestId = id(value)
            path == "response_message_id" -> messageId = id(value)
            path == "response" -> response(value as? JsonObject ?: throw EndpointChanged())
            path == "response/status" -> status(text(value))
            path == "response/fragments" -> {
                if (operation == "SET") fragments.clear()
                (value as? JsonArray ?: throw EndpointChanged()).forEach { add(it as? JsonObject ?: throw EndpointChanged()) }
            }
            path.matches(FRAGMENT_CONTENT) -> {
                val index = path.split('/')[2].toInt().let { if (it == -1) fragments.lastIndex else it }
                val fragment = fragments.getOrNull(index) ?: throw EndpointChanged()
                fragment.text = if (operation == "APPEND") fragment.text + text(value) else text(value)
            }
            // Per-fragment metadata on the live service: search results, step status, think timings.
            path.matches(FRAGMENT_FIELD) -> Unit
            path.isEmpty() || path.contains("content") || path.contains("fragments") -> throw EndpointChanged()
            // Token counts, search metadata and server timings do not alter displayed text.
        }
    }
    private fun root(value: JsonObject) {
        checkCodes(value)
        if (value.containsKey("error")) throw EndpointChanged()
        if (value.keys.none { it in setOf("request_message_id", "response_message_id", "response", "code", "biz_code", "data", "usage") }) {
            throw EndpointChanged()
        }
        value["request_message_id"]?.let { requestId = id(it) }
        value["response_message_id"]?.let { messageId = id(it) }
        value["response"]?.let { response(it as? JsonObject ?: throw EndpointChanged()) }
    }
    private fun response(value: JsonObject) {
        value["fragments"]?.let { list ->
            fragments.clear()
            (list as? JsonArray ?: throw EndpointChanged()).forEach { add(it as? JsonObject ?: throw EndpointChanged()) }
        }
        value["status"]?.let { status(text(it)) }
    }
    private fun status(value: String) {
        when (value) {
            "FINISHED" -> finished = true
            "WIP", "RUNNING", "PENDING" -> Unit
            else -> throw EndpointChanged()
        }
    }
    private fun add(value: JsonObject) {
        val type = value["type"]?.let(::text) ?: throw EndpointChanged()
        // SEARCH and other tool fragments keep their slot so "-1" stays aligned; only THINK and RESPONSE are shown.
        if (!type.matches(FRAGMENT_TYPE)) throw EndpointChanged()
        fragments += Fragment(type, value["content"]?.let { if (type in SHOWN) text(it) else it.toString() }.orEmpty())
    }
    private fun legacyDelta(value: JsonObject) {
        value["request_message_id"]?.let { requestId = id(it) }
        value["response_message_id"]?.let { messageId = id(it) }
        val choices = value["choices"] as? JsonArray ?: throw EndpointChanged()
        if (choices.size != 1) throw EndpointChanged()
        val choice = choices.single() as? JsonObject ?: throw EndpointChanged()
        val delta = choice["delta"] as? JsonObject ?: throw EndpointChanged()
        listOf("reasoning_content" to "THINK", "content" to "RESPONSE").forEach { (key, type) ->
            delta[key]?.takeUnless { it == JsonNull }?.let {
                val content = text(it)
                val last = fragments.lastOrNull()
                if (last?.type == type) last.text += content else fragments += Fragment(type, content)
            }
        }
        choice["finish_reason"]?.takeUnless { it == JsonNull }?.let {
            if (text(it) != "stop") throw EndpointChanged()
            finished = true
        }
    }
    private fun id(value: JsonElement): Long = (value as? JsonPrimitive)
        ?.takeIf { !it.isString }?.longOrNull?.takeIf { it >= 0 } ?: throw EndpointChanged()
    private fun text(value: JsonElement): String = (value as? JsonPrimitive)
        ?.takeIf { it.isString }?.content ?: throw EndpointChanged()

    private inline fun <T> safely(block: () -> T): T = try { block() }
    catch (error: SessionExpired) { throw error }
    catch (error: WebRateLimited) { throw error }
    catch (_: Exception) { throw EndpointChanged() }
}

/** Only inspect known envelope locations; never retain a server diagnostic. */
private fun checkCodes(value: JsonObject) {
    listOf("code", "biz_code").forEach { key ->
        value[key]?.let {
            val code = (it as? JsonPrimitive)?.takeIf { primitive -> !primitive.isString }?.intOrNull ?: throw EndpointChanged()
            checkWebCode(code)
        }
    }
    listOf("data", "error", "v").forEach { key -> (value[key] as? JsonObject)?.let(::checkCodes) }
}

internal fun checkWebCode(code: Int?) {
    when (code) {
        null, 0 -> Unit
        401, 403, 40001, 40002, 40003 -> throw SessionExpired()
        429 -> throw WebRateLimited()
        else -> throw EndpointChanged()
    }
}

private data class WebSseEvent(val name: String, val data: String)

/** Preserve named terminal events as well as data; bound framing before JSON parsing. */
private class WebSseFraming {
    private val line = StringBuilder()
    private val data = mutableListOf<String>()
    private var name = ""
    private var eventChars = 0
    private var skipLf = false

    fun feed(chunk: String): List<WebSseEvent> {
        val events = mutableListOf<WebSseEvent>()
        for (char in chunk) {
            if (skipLf && char == '\n') { skipLf = false; continue }
            skipLf = false
            when (char) {
                '\r', '\n' -> { consumeLine(events); skipLf = char == '\r' }
                else -> {
                    line.append(char)
                    if (line.length + eventChars > 2 * 1024 * 1024) throw EndpointChanged()
                }
            }
        }
        return events
    }
    fun finish(): List<WebSseEvent> {
        val events = mutableListOf<WebSseEvent>()
        if (line.isNotEmpty()) consumeLine(events)
        dispatch(events)
        return events
    }
    private fun consumeLine(events: MutableList<WebSseEvent>) {
        val value = line.toString()
        line.setLength(0)
        when {
            value.isEmpty() -> dispatch(events)
            value == "data" || value.startsWith("data:") -> {
                val field = if (value == "data") "" else value.substring(5).removePrefix(" ")
                eventChars += field.length + 1
                if (eventChars > 2 * 1024 * 1024) throw EndpointChanged()
                data += field
            }
            value.startsWith("event:") -> name = value.substring(6).trim()
        }
    }
    private fun dispatch(events: MutableList<WebSseEvent>) {
        if (data.isNotEmpty() || name in setOf("finish", "done")) events += WebSseEvent(name, data.joinToString("\n"))
        data.clear(); name = ""; eventChars = 0
    }
}
