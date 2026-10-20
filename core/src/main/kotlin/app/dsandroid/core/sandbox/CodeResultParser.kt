package app.dsandroid.core.sandbox

import kotlinx.serialization.json.*

/** evaluateJavascript returns a JSON string containing our result JSON, or null while running. */
object CodeResultParser {
    /** The page started and its worker is still running (the poll function answers ''). */
    fun isRunning(envelope: String): Boolean = envelope == "\"\""

    fun parse(envelope: String): CodeExecution? {
        if (isRunning(envelope)) return null
        if (envelope == "null") return null
        return runCatching {
            require(envelope.length <= CodeLimits.MAX_RESULT_JSON_CHARS * 6 + 2)
            val outer = Json.parseToJsonElement(envelope) as JsonPrimitive
            require(outer.isString && outer.content.length <= CodeLimits.MAX_RESULT_JSON_CHARS)
            val result = Json.parseToJsonElement(outer.content).jsonObject
            fun text(key: String): String = (result[key] as JsonPrimitive).also { require(it.isString) }.content
            fun flag(key: String): Boolean = (result[key] as JsonPrimitive).also { require(!it.isString) }.boolean
            CodeExecution(text("stdout"), text("stderr"), if (flag("hasValue")) text("value") else null,
                failed = flag("failed"), truncated = (result["truncated"] as? JsonPrimitive)?.booleanOrNull == true).bounded()
        }.getOrElse { CodeExecution(failed = true) }
    }
}
