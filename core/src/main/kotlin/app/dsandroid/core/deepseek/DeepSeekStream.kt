package app.dsandroid.core.deepseek

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

data class WebReply(
    val thinking: String = "", val response: String = "", val responseMessageId: Long? = null,
    val finished: Boolean = false, val requestMessageId: Long? = null,
    /** Seconds the service reports for its thinking fragments ("Thought for N seconds"); null when it reports none. */
    val thinkingSeconds: Double? = null,
    /**
     * The numbered sources of this reply (https only, at most [DeepSeekStream.MAX_CITATIONS]): the pages a Think + Search
     * turn read, or the results of a Search turn. Citation markers in [response] are `[citation:N]` with N counted in this list.
     */
    val citations: List<WebCitation> = emptyList(),
    /** Thinking stages and search steps in the order they arrived; empty unless the reply searched or used other tools. */
    val timeline: List<WebStep> = emptyList(),
    /** The chat's server time (epoch ms) after this reply, from its update_session event. */
    val sessionUpdatedAt: Long? = null,
    /** The title DeepSeek generated for the chat (its title event). */
    val title: String? = null,
)

/** One page a Search turn found or read; [site] and [icon] (DeepSeek's icon CDN) are empty when the service names none. */
@Serializable
data class WebCitation(val title: String, val url: String, val site: String = "", val icon: String = "")

/** A step of the grey thinking timeline, as the official app shows it. */
@Serializable
sealed interface WebStep {
    /** A thinking stage; [seconds] as the service reported it. */
    @Serializable @SerialName("think")
    data class Think(val text: String, val seconds: Double? = null) : WebStep
    /** "Found N web pages": what one search looked for and returned. */
    @Serializable @SerialName("search")
    data class Search(val label: String, val queries: List<String> = emptyList(), val results: List<WebCitation> = emptyList(), val done: Boolean = true) : WebStep
    /** "Read N pages": pages the service opened one after another. */
    @Serializable @SerialName("read")
    data class Read(val pages: List<WebCitation>, val done: Boolean = true) : WebStep
    /** Another tool step with the label the service gave it. */
    @Serializable @SerialName("tool")
    data class Tool(val label: String, val done: Boolean = true) : WebStep
}

/**
 * Independently implemented reducer for the web service's SSE fragment patches. Unknown events, paths, fields and fragment
 * types are ignored; only text that cannot be placed, unreadable JSON and wrong types of the fields that are shown fail.
 */
class DeepSeekStream {
    companion object {
        const val MAX_CITATIONS = 30
        private val METADATA_EVENTS = setOf("update_session", "title", "close", "update_file")
        private val FRAGMENT_PATH = Regex("response/fragments/(-?[0-9]{1,6})(?:/([a-z_]{1,40}))?")
        /** Reply states after which no FINISHED will come. */
        private val UNFINISHED = setOf("INCOMPLETE", "INTERRUPTED", "CONTENT_FILTER", "FAILED", "ERROR", "CANCELLED", "CANCELED", "TIMEOUT")
    }
    private val framing = WebSseFraming()
    private val fragments = mutableListOf<WebFragment>()
    /** A top-level patch without "p" or "o" inherits them from the previous top-level patch; batch children have their own. */
    private var topPath = ""
    private var topOperation = "APPEND"
    /** The last APPEND or SET at the top level, for a value patch that follows a batch. */
    private var topValueOperation = "APPEND"
    private var messageId: Long? = null
    private var requestId: Long? = null
    private var finished = false
    private var sessionUpdatedAt: Long? = null
    private var title: String? = null

    /** The reply after this chunk, or nothing when the chunk completed no event. */
    fun feed(chunk: String): List<WebReply> = safely {
        val events = framing.feed(chunk)
        events.forEach(::consume)
        if (events.isEmpty()) emptyList() else listOf(snapshot())
    }
    fun finish(): WebReply = safely {
        framing.finish().forEach(::consume)
        if (!finished) throw WebReplyInterrupted()
        snapshot()
    }
    private fun snapshot(): WebReply {
        val rendered = WebFragments.render(fragments, finished)
        return WebReply(rendered.thinking, rendered.response, messageId, finished, requestId, rendered.seconds, rendered.citations,
            rendered.timeline, sessionUpdatedAt, title)
    }
    private fun consume(event: WebSseEvent) {
        // Live service metadata events (session timestamps, generated titles, file states, close hints) carry no reply text;
        // they may follow FINISHED and may be cut off at the end of what was read, so they never fail a reply.
        if (event.name in METADATA_EVENTS) { metadata(event.name, event.data); return }
        if (finished) return
        val data = event.data
        if (data == "[DONE]") { finished = true; return }
        if (data.isBlank()) {
            if (event.name in setOf("finish", "done")) finished = true
            return
        }
        val json = Json.parseToJsonElement(data).jsonObject
        checkCodes(json)
        if (json.containsKey("error") || event.name == "error") throw EndpointChanged()
        json["request_message_id"]?.let { requestId = id(it) }
        json["response_message_id"]?.let { messageId = id(it) }
        when {
            "choices" in json -> legacyDelta(json)
            json.keys.any { it in setOf("p", "o", "v") } -> top(json)
            else -> root(json)
        }
        if (event.name in setOf("finish", "done")) finished = true
        if (fragments.sumOf { it.text.length } > 500_000) throw EndpointChanged()
    }
    private fun metadata(name: String, data: String) {
        val json = runCatching { Json.parseToJsonElement(data) as? JsonObject }.getOrNull() ?: return
        if (!finished) checkCodes(json)
        when (name) {
            "update_session" -> (json["updated_at"] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull
                ?.takeIf { it > 0 && it < 1e11 }?.let { sessionUpdatedAt = (it * 1000).toLong() }
            "title" -> (json["content"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()
                ?.takeIf { it.isNotEmpty() }?.let { title = it.take(100) }
        }
    }
    private fun top(patch: JsonObject) {
        val explicitPath = patch["p"]?.let(::text)?.trim('/')
        val explicitOperation = patch["o"]?.let(::text)
        val value = patch["v"]
        val path = explicitPath ?: topPath
        val operation = explicitOperation
            ?: if (topOperation == "BATCH" && value !is JsonArray) topValueOperation else topOperation
        if (explicitPath != null) topPath = path
        topOperation = operation
        if (operation != "BATCH") topValueOperation = operation
        apply(path, operation, value)
    }
    private fun apply(path: String, operation: String, value: JsonElement?) {
        if (value == null) return
        if (operation == "BATCH" || (value is JsonArray && isPatchList(value) && !isListPath(path))) {
            var childPath = ""
            var childOperation = "SET"
            (value as? JsonArray ?: throw EndpointChanged()).forEach { child ->
                val patch = child as? JsonObject ?: throw EndpointChanged()
                checkCodes(patch)
                patch["p"]?.let { childPath = text(it).trim('/') }
                patch["o"]?.let { childOperation = text(it) }
                apply(listOf(path, childPath).filter { it.isNotEmpty() }.joinToString("/"), childOperation, patch["v"])
            }
            return
        }
        if (operation != "APPEND" && operation != "SET") throw EndpointChanged()
        when {
            path.isEmpty() -> root(value as? JsonObject ?: throw EndpointChanged())
            path == "request_message_id" -> requestId = id(value)
            path == "response_message_id" -> messageId = id(value)
            path == "response" -> response(value as? JsonObject ?: throw EndpointChanged())
            path == "response/status" -> status(text(value))
            path == "response/fragments" -> {
                if (operation == "SET") fragments.clear()
                (value as? JsonArray ?: throw EndpointChanged()).forEach { add(it as? JsonObject ?: throw EndpointChanged()) }
            }
            path.startsWith("response/fragments/") -> fragmentPatch(path, operation, value)
            // Token counts, modes, pending flags and server timings do not alter what is shown.
            else -> Unit
        }
    }
    private fun isPatchList(value: JsonArray) = value.isNotEmpty() && value.all { it is JsonObject && "p" in it.jsonObject }
    private fun isListPath(path: String) = path == "response/fragments" ||
        path.endsWith("/results") || path.endsWith("/references") || path.endsWith("/queries")
    private fun fragmentPatch(path: String, operation: String, value: JsonElement) {
        // Deeper paths (a field inside a result, for example) are metadata.
        val match = FRAGMENT_PATH.matchEntire(path) ?: return
        val raw = match.groupValues[1].toInt()
        // Negative indices count from the end (-1 is the last fragment, -2 the one before); others are positions.
        val index = if (raw < 0) fragments.size + raw else raw
        val field = match.groupValues[2]
        val fragment = fragments.getOrNull(index)
        if (fragment == null) {
            // Text for a fragment that was never announced cannot be placed: that is a real protocol mismatch.
            if (field == "content" || field.isEmpty()) throw EndpointChanged()
            return
        }
        when (field) {
            "" -> fragments[index] = WebFragments.parse(value as? JsonObject ?: throw EndpointChanged())
            "content" -> WebFragments.setContent(fragment, value, append = operation == "APPEND")
            else -> WebFragments.setField(fragment, field, value, append = operation == "APPEND")
        }
    }
    private fun root(value: JsonObject) {
        checkCodes(value)
        if (value.containsKey("error")) throw EndpointChanged()
        value["request_message_id"]?.let { requestId = id(it) }
        value["response_message_id"]?.let { messageId = id(it) }
        value["response"]?.let { response(it as? JsonObject ?: throw EndpointChanged()) }
        // Anything else at the root (usage, modes, metadata added later) does not change what is shown.
    }
    private fun response(value: JsonObject) {
        if (messageId == null) WebFragments.optionalId(value["message_id"])?.let { messageId = it }
        if (requestId == null) WebFragments.optionalId(value["parent_id"])?.let { requestId = it }
        value["fragments"]?.let { list ->
            fragments.clear()
            (list as? JsonArray ?: throw EndpointChanged()).forEach { add(it as? JsonObject ?: throw EndpointChanged()) }
        }
        value["status"]?.let { status(text(it)) }
    }
    private fun status(value: String) {
        when (value) {
            "FINISHED" -> finished = true
            in UNFINISHED -> throw WebReplyInterrupted()
            // WIP, PENDING and states this client does not know: the stream decides how it ends.
            else -> Unit
        }
    }
    /** Every fragment keeps its slot, shown or not, so negative indices stay aligned with the service's list. */
    private fun add(value: JsonObject) { fragments += WebFragments.parse(value) }
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
                if (last?.type == type) last.text += content else fragments += WebFragment(null, type).also { fragment -> fragment.text = content }
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
    catch (error: Exception) {
        if (error is WebFailure) throw error
        throw EndpointChanged()
    }
}

/** One fragment of a web reply, from a stream or from history_messages. Only THINK and RESPONSE text is shown as text. */
internal class WebFragment(val id: Long?, val type: String) {
    var text = ""
    var status: String? = null
    var seconds: Double? = null
    var queries: List<String> = emptyList()
    var results: List<WebResult> = emptyList()
    var page: WebCitation? = null
    var references: List<WebReference> = emptyList()
    val shown get() = type == "THINK" || type == "RESPONSE"
}
internal data class WebResult(val citation: WebCitation, val citeIndex: Int?)
internal data class WebReference(val id: Long?, val type: String?)
internal class RenderedReply(val thinking: String, val response: String, val seconds: Double?, val citations: List<WebCitation>, val timeline: List<WebStep>)

/** Parses fragments and turns them into what the chat shows; shared by the stream reducer and the history decoder. */
internal object WebFragments {
    private const val MAX_RESULTS = 50
    /** Any citation or reference marker; one that cannot be resolved is removed, so raw markers never show. */
    private val MARKER = Regex("\\[(citation|reference):([^\\]\\n]{0,40})]")

    fun parse(value: JsonObject): WebFragment {
        val type = string(value["type"])?.take(40).orEmpty()
        val fragment = WebFragment(optionalId(value["id"]), type)
        value["content"]?.let { setContent(fragment, it, append = false) }
        listOf("status", "elapsed_secs", "queries", "results", "result", "references").forEach { key ->
            value[key]?.let { setField(fragment, key, it, append = false) }
        }
        return fragment
    }

    /** THINK and RESPONSE text must be text; other fragments keep a string content as their step label. */
    fun setContent(fragment: WebFragment, value: JsonElement, append: Boolean) {
        val content = string(value)
        when {
            content != null -> fragment.text = if (append) fragment.text + content else content
            value == JsonNull -> Unit
            fragment.shown -> throw EndpointChanged()
        }
    }

    fun setField(fragment: WebFragment, field: String, value: JsonElement, append: Boolean) {
        when (field) {
            "status" -> string(value)?.let { fragment.status = it.take(40) }
            "elapsed_secs" -> (value as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull
                ?.takeIf { it >= 0 && it < 86_400 }?.let { fragment.seconds = it }
            "queries" -> {
                val queries = (value as? JsonArray).orEmpty().mapNotNull { item ->
                    (item as? JsonObject)?.let { string(it["query"]) } ?: string(item)
                }.map { it.take(200) }
                fragment.queries = ((if (append) fragment.queries else emptyList()) + queries).take(10)
            }
            "results" -> {
                val results = (value as? JsonArray).orEmpty().mapNotNull { item ->
                    citation(item)?.let { WebResult(it, ((item as JsonObject)["cite_index"] as? JsonPrimitive)?.intOrNull) }
                }
                fragment.results = ((if (append) fragment.results else emptyList()) + results).distinctBy { it.citation.url }.take(MAX_RESULTS)
            }
            "result" -> citation(value)?.let { fragment.page = it }
            "references" -> {
                val references = (value as? JsonArray).orEmpty().mapNotNull { item ->
                    (item as? JsonObject)?.let { WebReference(optionalId(it["id"]), string(it["type"])) }
                }
                fragment.references = ((if (append) fragment.references else emptyList()) + references).take(200)
            }
        }
    }

    fun render(fragments: List<WebFragment>, finished: Boolean): RenderedReply {
        val citations = mutableListOf<WebCitation>()
        fun number(page: WebCitation): Int? {
            val known = citations.indexOfFirst { it.url == page.url }
            if (known >= 0) return known + 1
            if (citations.size >= DeepSeekStream.MAX_CITATIONS) return null
            citations += page
            return citations.size
        }
        val results = fragments.filter { it.type == "SEARCH" || it.type == "TOOL_SEARCH" }.flatMap { it.results }
        val opened = fragments.filter { it.type == "TOOL_OPEN" }.mapNotNull { it.page }
        // Numbers follow the list a person can open: the pages read, or else the search results in citation order.
        if (opened.isNotEmpty()) opened.forEach { number(it) }
        else results.sortedBy { it.citeIndex ?: Int.MAX_VALUE }.forEach { number(it.citation) }
        val response = fragments.filter { it.type == "RESPONSE" }.joinToString("") { fragment ->
            MARKER.replace(fragment.text) { match ->
                val n = match.groupValues[2].trim().takeIf { it.length in 1..4 && it.all(Char::isDigit) }?.toInt()
                val page = when {
                    n == null -> null
                    match.groupValues[1] == "citation" ->
                        results.firstOrNull { it.citeIndex == n }?.citation ?: results.getOrNull(n - 1)?.takeIf { it.citeIndex == null }?.citation
                    else -> fragment.references.getOrNull(n)?.id?.let { target -> fragments.firstOrNull { it.id == target } }?.page
                }
                page?.let(::number)?.let { "[citation:$it]" } ?: ""
            }
        }
        val thinks = fragments.filter { it.type == "THINK" }
        val steps = mutableListOf<WebStep>()
        fragments.forEach { fragment ->
            val done = fragment.status != "WIP" && fragment.status != "PENDING"
            when (fragment.type) {
                "THINK" -> steps += WebStep.Think(fragment.text, fragment.seconds)
                "RESPONSE" -> Unit
                "SEARCH", "TOOL_SEARCH" -> steps += WebStep.Search(fragment.text.trim().ifEmpty {
                    if (fragment.results.isEmpty()) "Searching the web" else "Found ${fragment.results.size} web pages"
                }.take(80), fragment.queries, fragment.results.map { it.citation }, done && (fragment.status != null || fragment.results.isNotEmpty()))
                "TOOL_OPEN" -> {
                    val last = steps.lastOrNull()
                    val pages = listOfNotNull(fragment.page)
                    if (last is WebStep.Read) steps[steps.lastIndex] = WebStep.Read((last.pages + pages).distinctBy { it.url }, last.done && done)
                    else steps += WebStep.Read(pages, done)
                }
                else -> if (fragment.text.isNotBlank()) steps += WebStep.Tool(fragment.text.trim().take(80), done)
            }
        }
        return RenderedReply(
            thinks.map { it.text }.filter { it.isNotEmpty() }.joinToString("\n\n"),
            if (finished) response else withoutPartialMarker(response),
            thinks.mapNotNull { it.seconds }.takeIf { it.isNotEmpty() }?.sum(),
            citations.toList(),
            if (steps.any { it !is WebStep.Think }) steps else emptyList(),
        )
    }

    /** While a reply streams, a marker can arrive in pieces ("[refer", "ence:0]"); its start is hidden until it completes. */
    private fun withoutPartialMarker(text: String): String {
        val open = text.lastIndexOf('[')
        if (open < 0 || text.indexOf(']', open) >= 0 || text.length - open > 20) return text
        val tail = text.substring(open + 1)
        val name = tail.substringBefore(':')
        val partial = if (':' in tail) name in setOf("reference", "citation") && tail.substringAfter(':').all(Char::isDigit)
            else "reference".startsWith(tail) || "citation".startsWith(tail)
        return if (partial) text.substring(0, open) else text
    }

    fun citation(value: JsonElement?): WebCitation? {
        val result = value as? JsonObject ?: return null
        val url = string(result["url"])?.takeIf { it.startsWith("https://") && it.length <= 2048 && it.none(Char::isWhitespace) } ?: return null
        val host = url.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#').take(253)
        val title = string(result["title"])?.trim()?.take(200).orEmpty()
        val site = string(result["site_name"])?.trim()?.take(80).orEmpty()
        val icon = string(result["site_icon"])?.takeIf { it.startsWith("https://") && it.length <= 512 && it.none(Char::isWhitespace) }.orEmpty()
        return WebCitation(title.ifEmpty { host }, url, site, icon)
    }

    fun optionalId(value: JsonElement?): Long? = (value as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull?.takeIf { it >= 0 }
    private fun string(value: JsonElement?): String? = (value as? JsonPrimitive)?.takeIf { it.isString }?.content
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
        // 40300 MISSING_HEADER, 40301 INVALID_POW_RESPONSE: the proof of work was missing, stale or wrong.
        in 40300..40399 -> throw WebPowRejected()
        in 500..599 -> throw WebServiceBusy()
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
