package app.dsandroid.core.deepseek

import app.dsandroid.core.llm.SseParser
import kotlinx.serialization.json.*

data class WebReply(val thinking: String = "", val response: String = "", val responseMessageId: Long? = null, val finished: Boolean = false)

/** Independently implemented reducer for web-client fragment patches; SSE framing is shared. */
class DeepSeekStream {
    private data class Fragment(val type: String, var text: String)
    private val framing = SseParser()
    private val fragments = mutableListOf<Fragment>()
    private var lastPath = ""
    private var lastOperation = "APPEND"
    private var messageId: Long? = null
    private var finished = false

    fun feed(chunk: String): List<WebReply> = framing.feed(chunk).map { consume(it); snapshot() }
    fun finish(): WebReply {
        framing.finish().forEach(::consume)
        if (!finished) throw UpdateRequired()
        return snapshot()
    }
    private fun snapshot() = WebReply(
        fragments.filter { it.type == "THINK" }.joinToString("") { it.text },
        fragments.filter { it.type == "RESPONSE" }.joinToString("") { it.text }, messageId, finished,
    )
    private fun consume(data: String) {
        if (data == "[DONE]") { finished = true; return }
        if (data.isBlank()) return
        try {
            val json = Json.parseToJsonElement(data).jsonObject
            checkWebCode(json["code"]?.jsonPrimitive?.intOrNull)
            if (json.containsKey("error")) throw UpdateRequired()
            json["response_message_id"]?.jsonPrimitive?.longOrNull?.let { messageId = it }
            apply(json)
            if (fragments.sumOf { it.text.length } > 500_000) throw UpdateRequired()
        } catch (error: SessionExpired) { throw error }
        catch (error: WebRateLimited) { throw error }
        catch (_: Exception) { throw UpdateRequired() }
    }
    private fun apply(patch: JsonObject, prefix: String = "") {
        val explicitPath = patch["p"]?.jsonPrimitive?.content
        val path = if (explicitPath == null) lastPath else listOf(prefix, explicitPath.trim('/')).filter { it.isNotEmpty() }.joinToString("/")
        val operation = patch["o"]?.jsonPrimitive?.content ?: lastOperation
        val value = patch["v"] ?: return
        if (explicitPath != null) lastPath = path
        if (operation == "BATCH") {
            value.jsonArray.forEach { apply(it.jsonObject, path) }
            return
        }
        if (operation !in setOf("APPEND", "SET")) throw UpdateRequired()
        lastOperation = operation
        when {
            path.isEmpty() && value is JsonObject -> root(value)
            path == "response_message_id" -> messageId = value.jsonPrimitive.long
            path == "response" && value is JsonObject -> response(value)
            path == "response/status" -> status(value.jsonPrimitive.content)
            path == "response/fragments" -> {
                if (operation == "SET") fragments.clear()
                value.jsonArray.forEach { add(it.jsonObject) }
            }
            path.matches(Regex("response/fragments/(-1|[0-9]+)/content")) -> {
                val index = path.split('/')[2].toInt().let { if (it == -1) fragments.lastIndex else it }
                val fragment = fragments.getOrNull(index) ?: throw UpdateRequired()
                val text = value.jsonPrimitive.takeIf { it.isString }?.content ?: throw UpdateRequired()
                fragment.text = if (operation == "APPEND") fragment.text + text else text
            }
            path.contains("content") || path.contains("fragments") -> throw UpdateRequired()
            // Token counts, search metadata and server timings do not alter displayed text.
        }
    }
    private fun root(value: JsonObject) {
        checkWebCode(value["code"]?.jsonPrimitive?.intOrNull)
        value["response_message_id"]?.jsonPrimitive?.longOrNull?.let { messageId = it }
        (value["response"] as? JsonObject)?.let(::response)
    }
    private fun response(value: JsonObject) {
        value["fragments"]?.jsonArray?.let { list -> fragments.clear(); list.forEach { add(it.jsonObject) } }
        value["status"]?.jsonPrimitive?.content?.let(::status)
    }
    private fun status(value: String) {
        if (value == "INTERRUPTED") throw UpdateRequired()
        if (value == "FINISHED") finished = true
    }
    private fun add(value: JsonObject) {
        val type = value.getValue("type").jsonPrimitive.content
        if (type !in setOf("THINK", "RESPONSE")) throw UpdateRequired()
        val text = value["content"]?.jsonPrimitive?.content.orEmpty()
        fragments += Fragment(type, text)
    }
}

internal fun checkWebCode(code: Int?) {
    when (code) {
        null, 0 -> Unit
        401, 403 -> throw SessionExpired()
        429 -> throw WebRateLimited()
        else -> throw UpdateRequired()
    }
}
