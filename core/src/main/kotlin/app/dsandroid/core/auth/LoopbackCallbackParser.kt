package app.dsandroid.core.auth

import java.net.URI
import java.net.URLDecoder

object LoopbackCallbackParser {
    fun parse(requestLine: String, expectedState: String): String {
        require(requestLine.length <= 8192) { "Callback too long" }
        val parts = requestLine.split(' ')
        require(parts.size == 3 && parts[0] == "GET" && parts[2] in listOf("HTTP/1.0", "HTTP/1.1")) { "Invalid callback request" }
        val uri = try { URI(parts[1]) } catch (_: Exception) { throw IllegalArgumentException("Invalid callback URL") }
        require(!uri.isAbsolute && uri.rawAuthority == null && uri.rawPath == "/oauth/callback" && uri.rawFragment == null) { "Wrong callback path" }
        val params = linkedMapOf<String, String>()
        uri.rawQuery.orEmpty().split('&').filter { it.isNotEmpty() }.forEach { field ->
            val pair = field.split('=', limit = 2)
            val name = URLDecoder.decode(pair[0], "UTF-8")
            val value = URLDecoder.decode(pair.getOrElse(1) { "" }, "UTF-8")
            require(!params.containsKey(name)) { "Duplicate callback parameter" }
            params[name] = value
        }
        require(OAuthState.matches(expectedState, params["state"])) { "OAuth state mismatch" }
        require(!params.containsKey("error")) { "Authorization declined or unavailable; use an API key" }
        return params["code"]?.takeIf { it.isNotBlank() } ?: throw IllegalArgumentException("Missing authorization code")
    }
}
