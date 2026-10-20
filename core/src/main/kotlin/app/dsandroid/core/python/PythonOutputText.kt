package app.dsandroid.core.python

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/** run_python's JSON result as readable text for the editor's output pane. */
object PythonOutputText {
    fun format(result: String): String {
        val json = runCatching { Json.parseToJsonElement(result).jsonObject }.getOrNull() ?: return result.take(PythonTools.MAX_OUTPUT_CHARS)
        fun text(key: String) = (json[key] as? JsonPrimitive)?.contentOrNull.orEmpty().trimEnd()
        val files = (json["files"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        return buildList {
            if (text("stdout").isNotEmpty()) add(text("stdout"))
            if (text("stderr").isNotEmpty()) add("stderr:\n" + text("stderr"))
            if (text("error").isNotEmpty()) add("Error: " + text("error"))
            if ((json["timed_out"] as? JsonPrimitive)?.booleanOrNull == true) add("Stopped at the time limit; Python restarts on the next run.")
            if ((json["truncated"] as? JsonPrimitive)?.booleanOrNull == true) add("Output was shortened.")
            if (files.isNotEmpty()) add("Files in this chat: " + files.joinToString())
        }.joinToString("\n\n").ifEmpty { "Finished with no output." }
    }
}
