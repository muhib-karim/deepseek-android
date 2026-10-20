package app.dsandroid.core.deepseek

import java.net.URI
import java.util.Base64
import kotlinx.coroutines.CopyableThrowable
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
        // The bearer token is what the service authenticates (invalid token -> code 40003). Cookies are passed through
        // as the WebView holds them (WAF token etc.); no specific cookie is required.
        if (token.isExpired(nowMillis) || cookies.any { it == '\r' || it == '\n' }) throw SessionExpired()
    }
}

enum class ChatRoute { LOGIN, DEEPSEEK, API_KEY }
fun sessionRoute(token: SessionToken?, apiFallbackChosen: Boolean, nowMillis: Long): ChatRoute = when {
    apiFallbackChosen -> ChatRoute.API_KEY
    token == null || token.isExpired(nowMillis) -> ChatRoute.LOGIN
    else -> ChatRoute.DEEPSEEK
}

/**
 * A typed, user-facing failure of the web route. Each message is fixed text (never a server body) and each class refuses
 * coroutine stack-trace copies, so no cause chain can carry server data into the UI or logs.
 */
sealed interface WebFailure

/** DeepSeek rejected the session (401, auth codes): the app locks back to the welcome screen. */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionExpired : Exception("Your DeepSeek session expired. Please sign in again."), CopyableThrowable<SessionExpired>, WebFailure {
    override fun createCopy(): SessionExpired? = null
}
/** Only for answers whose shape this client cannot read (unknown envelope, unparseable JSON, missing required fields). */
@OptIn(ExperimentalCoroutinesApi::class)
class EndpointChanged : Exception("DeepSeek's web service changed and this app can't talk to it right now. Try again later, update the app, or switch to API-key mode in Settings."), CopyableThrowable<EndpointChanged>, WebFailure {
    override fun createCopy(): EndpointChanged? = null
}

/** Source compatibility for callers of the original protocol error. */
typealias UpdateRequired = EndpointChanged
@OptIn(ExperimentalCoroutinesApi::class)
class WebRateLimited : Exception("DeepSeek is busy or has limited requests. Try again later."), CopyableThrowable<WebRateLimited>, WebFailure {
    override fun createCopy(): WebRateLimited? = null
}
/** The phone could not reach DeepSeek or the connection dropped (timeouts, DNS, TLS, a body cut off in transit). */
@OptIn(ExperimentalCoroutinesApi::class)
class WebConnectionFailed : Exception("Couldn't reach DeepSeek. Check your connection, then tap Retry."), CopyableThrowable<WebConnectionFailed>, WebFailure {
    override fun createCopy(): WebConnectionFailed? = null
}
/** DeepSeek answered with a server error (HTTP 5xx). */
@OptIn(ExperimentalCoroutinesApi::class)
class WebServiceBusy : Exception("DeepSeek is having trouble right now. Tap Retry in a moment."), CopyableThrowable<WebServiceBusy>, WebFailure {
    override fun createCopy(): WebServiceBusy? = null
}
/** The reply stream ended, or DeepSeek marked the reply incomplete, before it finished. */
@OptIn(ExperimentalCoroutinesApi::class)
class WebReplyInterrupted : Exception("The reply stopped before it finished. Tap Retry to ask again."), CopyableThrowable<WebReplyInterrupted>, WebFailure {
    override fun createCopy(): WebReplyInterrupted? = null
}
/** DeepSeek did not accept the proof of work (codes 40300-40399); a fresh challenge usually passes. */
@OptIn(ExperimentalCoroutinesApi::class)
class WebPowRejected : Exception("DeepSeek's request check did not pass. Tap Retry."), CopyableThrowable<WebPowRejected>, WebFailure {
    override fun createCopy(): WebPowRejected? = null
}
/** An uploaded file that DeepSeek could not process, or is still processing after the wait; [fileName] is the user's own name. */
@OptIn(ExperimentalCoroutinesApi::class)
class WebFileFailed(val fileName: String, val stillProcessing: Boolean = false) : Exception(
    if (stillProcessing) "DeepSeek is still processing “$fileName”. Tap Retry in a moment."
    else "DeepSeek couldn't process “$fileName”. Remove it or try another file."), CopyableThrowable<WebFileFailed>, WebFailure {
    override fun createCopy(): WebFileFailed? = null
}

/** Failures worth one automatic retry when nothing of the reply has arrived yet: the network, the server, the request check. */
fun Throwable.isTransientWebFailure(): Boolean = this is WebConnectionFailed || this is WebServiceBusy || this is WebPowRejected || this is WebReplyInterrupted
