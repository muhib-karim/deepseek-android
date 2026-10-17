package app.dsandroid.auth

import app.dsandroid.core.llm.LlmJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

object AccountTokenParser {
    fun parse(response: String): String {
        val fields = LlmJson.parseToJsonElement(response) as? JsonObject
            ?: throw IllegalArgumentException("Invalid account response")
        return listOf("access_token", "auth_token").mapNotNull { fields[it] as? JsonPrimitive }
            .firstOrNull { it.isString && it.content.isNotBlank() && it.content.all { char -> char.code in 32..126 } }
            ?.content ?: throw IllegalArgumentException("Account endpoint returned no usable token")
    }
}
