package app.dsandroid.core.phone

import app.dsandroid.core.tools.*
import kotlinx.serialization.json.*

sealed interface PhoneAction {
    data class Sms(val to: String, val body: String) : PhoneAction
    data class Email(val to: String, val subject: String, val body: String) : PhoneAction
    data class Calendar(val title: String, val beginMillis: Long, val endMillis: Long, val location: String, val description: String) : PhoneAction
    data class Map(val query: String) : PhoneAction
}

object PhoneActions {
    fun parse(name: String, args: JsonObject): PhoneAction {
        fun text(key: String, max: Int, blank: Boolean = false): String = args.stringArg(key).also {
            require(it.length <= max && (blank || it.isNotBlank()) && it.none { c -> c == '\u0000' })
        }
        val keys = when (name) {
            "draft_sms" -> setOf("to", "body")
            "draft_email" -> setOf("to", "subject", "body")
            "add_calendar_event" -> setOf("title", "beginMillis", "endMillis", "location", "description")
            "open_map" -> setOf("query")
            else -> throw IllegalArgumentException("Unsupported phone action")
        }
        require(args.keys == keys)
        return when (name) {
            "draft_sms" -> PhoneAction.Sms(text("to", 40).also {
                require(it.matches(Regex("\\+?[0-9][0-9 ()-]{1,38}")))
            }, text("body", 8000))
            "draft_email" -> PhoneAction.Email(text("to", 254).also {
                require(it.matches(Regex("[A-Za-z0-9.!#$%&'*+/=^_`{|}~-]+@[A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)+")))
            }, text("subject", 300, true).also { require('\r' !in it && '\n' !in it) }, text("body", 8000))
            "add_calendar_event" -> {
                fun time(key: String): Long {
                    val primitive = args[key] as? JsonPrimitive
                    require(primitive != null && !primitive.isString)
                    return requireNotNull(primitive.longOrNull).also { require(it in 0..253402300799999L) }
                }
                val begin = time("beginMillis"); val end = time("endMillis")
                require(end > begin)
                PhoneAction.Calendar(text("title", 300), begin, end, text("location", 1000, true), text("description", 8000, true))
            }
            else -> PhoneAction.Map(text("query", 1000))
        }
    }
}

/** Only the existing agent guard may authorize execution. No arbitrary intent/URI/flag input. */
class PhoneActionTool(override val name: String, private val launch: suspend (PhoneAction) -> ToolResult) : Tool {
    init { require(name in NAMES) }
    override val risk = Risk.CONFIRM
    override val kind = ToolKind.ACTION
    override val description = "Open a standard phone app for $name after per-action approval. The user finishes the draft/event there. Never sends or saves automatically."
    override val schema = objectSchema(when (name) {
        "draft_sms" -> mapOf("to" to stringSchema("One phone number"), "body" to stringSchema("Exact SMS draft"))
        "draft_email" -> mapOf("to" to stringSchema("One email address"), "subject" to stringSchema("Subject"), "body" to stringSchema("Exact email draft"))
        "add_calendar_event" -> mapOf("title" to stringSchema("Title"), "beginMillis" to timeSchema(), "endMillis" to timeSchema(), "location" to stringSchema("Location or empty"), "description" to stringSchema("Description or empty"))
        else -> mapOf("query" to stringSchema("Place name, address or coordinates; no live location access"))
    })
    fun valid(args: JsonObject): Boolean = runCatching { PhoneActions.parse(name, args) }.isSuccess
    override suspend fun run(args: JsonObject, ctx: ToolContext) = launch(PhoneActions.parse(name, args))
    companion object {
        val NAMES = listOf("draft_sms", "draft_email", "add_calendar_event", "open_map")
        private fun timeSchema() = buildJsonObject { put("type", "integer"); put("description", "Epoch milliseconds including the intended timezone offset") }
    }
}
