package app.dsandroid.core.deepseek

import java.net.URI
import java.util.Base64
import kotlinx.serialization.json.*

object DeepSeekOrigin {
    const val URL = "https://chat.deepseek.com"
    fun isTrusted(url: String?): Boolean = runCatching {
        val uri = URI(url ?: return false)
        uri.scheme == "https" && uri.host == "chat.deepseek.com" && uri.rawUserInfo == null && uri.port in setOf(-1, 443)
    }.getOrDefault(false)
}

/** Deliberately not a data class: debug strings must never contain the credential. */
class SessionToken private constructor(private val value: String) {
    fun bearer(): String = value
    override fun toString() = "SessionToken([redacted])"

    // This is an early expiry hint, not JWT signature verification. The server remains authoritative.
    fun isExpired(nowMillis: Long): Boolean {
        val parts = value.split('.')
        if (parts.size != 3) return false
        val exp = runCatching {
            Json.parseToJsonElement(String(Base64.getUrlDecoder().decode(parts[1]), Charsets.UTF_8))
                .jsonObject["exp"]?.jsonPrimitive?.longOrNull
        }.getOrNull() ?: return false
        return nowMillis / 1000 >= exp
    }

    companion object {
        fun fromJavascript(result: String?): SessionToken? {
            val primitive = runCatching { Json.parseToJsonElement(result ?: return null) as? JsonPrimitive }.getOrNull()
            return if (primitive?.isString == true) parse(primitive.content) else null
        }
        fun parse(storage: String?): SessionToken? {
            val raw = storage?.trim()?.takeIf { it.length in 1..16384 } ?: return null
            val value = when (raw.first()) {
                '{', '[', '"' -> {
                    val parsed = runCatching { Json.parseToJsonElement(raw) }.getOrNull()
                    val field = if (parsed is JsonObject) parsed["value"] else parsed
                    (field as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
                }
                else -> raw
            }
            if (value in setOf("null", "true", "false") || value.toDoubleOrNull() != null) return null
            if (!value.matches(Regex("[A-Za-z0-9._~+/-]{1,16384}=*"))) return null
            return SessionToken(value)
        }
    }
}

class WebCredentials(val token: SessionToken, val cookies: String) {
    override fun toString() = "WebCredentials([redacted])"
    fun requireUsable(nowMillis: Long) {
        if (token.isExpired(nowMillis) || cookies.isBlank() || cookies.any { it == '\r' || it == '\n' } ||
            cookies.split(';').none { it.trim().startsWith("ds_session_id=") && it.substringAfter('=').isNotBlank() }) throw SessionExpired()
    }
}

enum class ChatRoute { LOGIN, DEEPSEEK, API_KEY }
fun sessionRoute(token: SessionToken?, apiFallbackChosen: Boolean, nowMillis: Long): ChatRoute = when {
    apiFallbackChosen -> ChatRoute.API_KEY
    token == null || token.isExpired(nowMillis) -> ChatRoute.LOGIN
    else -> ChatRoute.DEEPSEEK
}

class SessionExpired : Exception("Your DeepSeek session expired. Please sign in again.")
class EndpointChanged : Exception("DeepSeek's web service changed and this app can't talk to it right now. Try again later, update the app, or switch to API-key mode in Settings.")

/** Source compatibility for callers of the original protocol error. */
typealias UpdateRequired = EndpointChanged
class WebRateLimited : Exception("DeepSeek is busy or has limited requests. Try again later.")
class WebConnectionFailed : Exception("Could not reach DeepSeek. Check your connection and try again.")
