package app.dsandroid.core.deepseek

import kotlinx.serialization.json.*

/** One chat in DeepSeek's list (GET chat_session/fetch_page); [updatedAt] is epoch milliseconds, [cursor] the server's own value. */
data class ServerSession(val id: String, val title: String, val pinned: Boolean, val updatedAt: Long, val cursor: String)

/**
 * A chat read back from DeepSeek (GET chat/history_messages): the branch that ends at its current message, oldest first.
 * [currentMessageId] is the parent for the next message; null means the branch could not be found, and the chat must not
 * be continued (a parent is never guessed).
 */
data class ServerChat(val session: ServerSession, val currentMessageId: Long?, val messages: List<WebMessage>)

/** Decoders for DeepSeek's chat list and history, and the merge of that list into local history. */
object ServerHistory {
    private const val AGENT_PREAMBLE = "[Agent mode: instructions from the DeepSeek Android app]"
    private const val USER_REQUEST = "\n\nUser request:\n"
    private const val TOOL_RESULT = "```tool_result"
    private const val REPAIR = "Your previous reply contained an invalid tool block:"
    private const val MAX_MESSAGES = 2000

    /** A page of the chat list and whether DeepSeek has more after it. */
    fun sessions(data: JsonObject): Pair<List<ServerSession>, Boolean> {
        val list = data["chat_sessions"] as? JsonArray ?: throw EndpointChanged()
        val sessions = list.mapNotNull { item -> (item as? JsonObject)?.let(::session) }
        return sessions to ((data["has_more"] as? JsonPrimitive)?.booleanOrNull ?: false)
    }

    fun session(value: JsonObject): ServerSession? {
        val id = string(value["id"])?.takeIf { it.isNotBlank() && it.length <= 256 } ?: return null
        val updated = value["updated_at"] as? JsonPrimitive
        val seconds = updated?.takeIf { !it.isString }?.doubleOrNull?.takeIf { it > 0 && it < 1e11 } ?: 0.0
        return ServerSession(id, string(value["title"])?.trim()?.take(100).orEmpty(), (value["pinned"] as? JsonPrimitive)?.booleanOrNull ?: false,
            Math.round(seconds * 1000), updated?.content?.takeIf { seconds > 0 }.orEmpty())
    }

    /** The branch ending at chat_session.current_message_id, as chat messages; tool fragments are display data only. */
    fun chat(data: JsonObject): ServerChat {
        val session = (data["chat_session"] as? JsonObject)?.let(::session) ?: throw EndpointChanged()
        val current = WebFragments.optionalId((data["chat_session"] as JsonObject)["current_message_id"])
        val byId = (data["chat_messages"] as? JsonArray ?: throw EndpointChanged())
            .mapNotNull { it as? JsonObject }
            .mapNotNull { message -> WebFragments.optionalId(message["message_id"])?.let { it to message } }
            .toMap()
        if (current == null || current !in byId) return ServerChat(session, null, emptyList())
        val branch = mutableListOf<JsonObject>()
        val seen = mutableSetOf<Long>()
        var next: Long? = current
        while (next != null && seen.add(next) && branch.size < MAX_MESSAGES) {
            val message = byId[next] ?: break
            branch += message
            next = WebFragments.optionalId(message["parent_id"])
        }
        val messages = branch.asReversed().mapNotNull { message(it) }
        return ServerChat(session, current, messages)
    }

    private fun message(value: JsonObject): WebMessage? {
        val id = WebFragments.optionalId(value["message_id"]) ?: return null
        val parent = WebFragments.optionalId(value["parent_id"])
        val thinking = (value["thinking_enabled"] as? JsonPrimitive)?.booleanOrNull ?: false
        val search = (value["search_enabled"] as? JsonPrimitive)?.booleanOrNull ?: false
        val fragments = (value["fragments"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        return when (string(value["role"])) {
            "USER" -> {
                val request = fragments.filter { string(it["type"]) == "REQUEST" }.joinToString("") { string(it["content"]).orEmpty() }
                // This app's own agent protocol messages: the request is shown without the instructions; tool results and
                // repair prompts are not user messages to show.
                if (request.startsWith(TOOL_RESULT) || request.startsWith(REPAIR)) return null
                val agent = request.startsWith(AGENT_PREAMBLE)
                val shown = if (agent && USER_REQUEST in request) request.substringAfterLast(USER_REQUEST) else request
                val files = fragments.filter { string(it["type"]) == "FILE" }.flatMap { (it["files"] as? JsonArray).orEmpty() }
                    .mapNotNull { file -> (file as? JsonObject)?.let(::attachment) }
                WebMessage("s$id", true, shown, serverId = id, parentId = parent, prompt = request, thinkingEnabled = thinking,
                    searchEnabled = search, refFileIds = files.mapNotNull { it.id }, files = files)
            }
            "ASSISTANT" -> {
                val rendered = WebFragments.render(fragments.map(WebFragments::parse), finished = true)
                val feedback = ((value["feedback"] as? JsonObject)?.get("feedback_type") as? JsonPrimitive)?.contentOrNull?.takeIf { it == "GOOD" || it == "BAD" }
                WebMessage("s$id", false, rendered.response, rendered.thinking, serverId = id, parentId = parent, thinkingEnabled = thinking,
                    searchEnabled = search, thinkingSeconds = rendered.seconds, citations = rendered.citations, feedback = feedback,
                    timeline = rendered.timeline)
            }
            else -> null
        }
    }

    private fun attachment(value: JsonObject): WebAttachment? {
        val name = string(value["file_name"])?.trim()?.takeIf { it.isNotEmpty() }?.take(200) ?: return null
        val bytes = (value["file_size"] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull?.takeIf { it >= 0 }
        return WebAttachment(name, bytes, string(value["id"])?.take(256))
    }

    /**
     * Merges DeepSeek's chat list into local history. Chats are matched by session id; DeepSeek's title (when it has one,
     * and not for agent chats) and pin win for chats it keeps. Listed chats unknown here become remote entries. When the list is [complete], a local
     * chat whose session DeepSeek no longer lists was deleted elsewhere: without local files it goes, otherwise it stays as a
     * read-only local chat. Chats that exist only here are kept as they are.
     */
    fun merge(local: List<WebChat>, server: List<ServerSession>, complete: Boolean, openId: String?, newId: (ServerSession) -> String): List<WebChat> {
        val listed = server.associateBy { it.id }
        val merged = local.mapNotNull { chat ->
            val session = chat.sessionId?.let(listed::get)
            when {
                // DeepSeek titles an agent chat after this app's tool instructions, so agent chats keep their own title.
                session != null -> chat.copy(title = session.title.takeIf { chat.messages.none { it.agent } }?.ifBlank { null } ?: chat.title,
                    pinned = session.pinned, updatedAt = maxOf(chat.updatedAt, session.updatedAt), remoteUpdatedAt = session.updatedAt)
                chat.sessionId == null || !complete -> chat
                chat.remote -> null
                chat.id != openId && chat.messages.none { it.artifacts.isNotEmpty() || it.codeRuns.isNotEmpty() || it.agent } -> null
                else -> chat.copy(sessionId = null, parentId = null, blocked = true, pinned = false)
            }
        }
        val known = local.mapNotNull { it.sessionId }.toSet()
        val added = server.filter { it.id !in known }.map { session ->
            WebChat(newId(session), session.title.ifBlank { "New chat" }, session.id, updatedAt = session.updatedAt, pinned = session.pinned,
                remote = true, remoteUpdatedAt = session.updatedAt)
        }
        return (merged + added).sortedByDescending { it.updatedAt }.take(WebHistory.MAX_CHATS)
    }

    private fun string(value: JsonElement?): String? = (value as? JsonPrimitive)?.takeIf { it.isString }?.content
}
