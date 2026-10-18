package app.dsandroid.core.deepseek

import java.util.Base64
import kotlinx.serialization.json.*

data class PowChallenge(
    val algorithm: String, val challenge: String, val salt: String,
    val difficulty: Long, val expireAt: Long, val signature: String,
) {
    fun requireUsable(nowMillis: Long) {
        if (algorithm != "DeepSeekHashV1" || challenge.isBlank() || salt.isBlank() || signature.isBlank() ||
            difficulty !in 1..9_007_199_254_740_991L || nowMillis >= expireAt) throw UpdateRequired()
    }
    fun workerMessage(): JsonObject = buildJsonObject {
        put("type", "pow-challenge")
        putJsonObject("challenge") {
            put("algorithm", algorithm); put("challenge", challenge); put("salt", salt)
            put("difficulty", difficulty); put("expireAt", expireAt); put("signature", signature)
        }
    }
    fun header(answer: Long): String {
        if (algorithm != "DeepSeekHashV1" || answer < 0 || answer >= difficulty) throw UpdateRequired()
        val json = buildJsonObject {
            put("algorithm", algorithm); put("challenge", challenge); put("salt", salt)
            put("answer", answer); put("signature", signature); put("target_path", COMPLETION_PATH)
        }
        return Base64.getEncoder().encodeToString(json.toString().toByteArray(Charsets.UTF_8))
    }
    override fun toString() = "PowChallenge([redacted])"
    companion object {
        const val COMPLETION_PATH = "/api/v0/chat/completion"
        const val WORKER_URL = "https://fe-static.deepseek.com/chat/static/76608.8f2a9fa413.js"
        fun parse(json: JsonObject): PowChallenge = try {
            PowChallenge(json.getValue("algorithm").jsonPrimitive.content, json.getValue("challenge").jsonPrimitive.content,
                json.getValue("salt").jsonPrimitive.content, json.getValue("difficulty").jsonPrimitive.long,
                json.getValue("expire_at").jsonPrimitive.long, json.getValue("signature").jsonPrimitive.content)
        } catch (_: Exception) { throw UpdateRequired() }
    }
}
