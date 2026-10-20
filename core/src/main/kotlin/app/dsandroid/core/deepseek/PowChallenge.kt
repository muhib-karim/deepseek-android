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
    /** X-DS-PoW-Response for the request at [targetPath]; DeepSeek checks it against the path the challenge was created for. */
    fun header(answer: Long, targetPath: String = COMPLETION_PATH): String {
        if (algorithm != "DeepSeekHashV1" || answer < 0 || answer >= difficulty || targetPath !in TARGET_PATHS) throw UpdateRequired()
        val json = buildJsonObject {
            put("algorithm", algorithm); put("challenge", challenge); put("salt", salt)
            put("answer", answer); put("signature", signature); put("target_path", targetPath)
        }
        return Base64.getEncoder().encodeToString(json.toString().toByteArray(Charsets.UTF_8))
    }
    override fun toString() = "PowChallenge([redacted])"
    companion object {
        const val COMPLETION_PATH = "/api/v0/chat/completion"
        /** File uploads need their own proof of work since October 2026 (without it: code 40300 MISSING_HEADER). */
        const val UPLOAD_PATH = "/api/v0/file/upload_file"
        val TARGET_PATHS = setOf(COMPLETION_PATH, UPLOAD_PATH)
        const val WORKER_URL = "https://fe-static.deepseek.com/chat/static/76608.8f2a9fa413.js"
        fun parse(json: JsonObject): PowChallenge = try {
            fun text(key: String): String = (json[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: throw EndpointChanged()
            fun number(key: String): Long = (json[key] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
                ?: throw EndpointChanged()
            PowChallenge(text("algorithm"), text("challenge"), text("salt"), number("difficulty"),
                number("expire_at"), text("signature"))
        } catch (_: Exception) { throw UpdateRequired() }
    }
}
