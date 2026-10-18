package app.dsandroid.deepseek

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebStorage
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import app.dsandroid.core.deepseek.DeepSeekOrigin
import app.dsandroid.core.deepseek.SessionToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/** Independent of the legacy API settings and excluded from Android backups by the manifest. */
@Suppress("DEPRECATION")
class DeepSeekSessionStore(context: Context) {
    private val preferences = EncryptedSharedPreferences.create(
        context.applicationContext, "deepseek_web_session",
        MasterKey.Builder(context.applicationContext).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )
    fun load(): SessionToken? = SessionToken.parse(preferences.getString("session", null))
    fun save(token: SessionToken) {
        check(preferences.edit().putString("session", token.bearer()).commit()) { "Could not securely save the DeepSeek session." }
    }
    fun clear() { check(preferences.edit().remove("session").commit()) { "Could not clear the saved DeepSeek session." } }
}

object DeepSeekCookies {
    // Never ask CookieManager for a URL supplied by a page, model, redirect or user.
    fun read(): String = CookieManager.getInstance().getCookie(DeepSeekOrigin.URL).orEmpty()

    suspend fun clear() = withContext(Dispatchers.Main.immediate) {
        val manager = CookieManager.getInstance()
        val paths = listOf("/", "/api", "/api/v0", "/api/v0/chat", "/api/v0/chat_session")
        val names = paths.flatMap { path ->
            manager.getCookie(DeepSeekOrigin.URL + path).orEmpty().split(';').map { it.substringBefore('=').trim() }
        }.filter { it.matches(Regex("[A-Za-z0-9_!#$%&'*+.^`|~-]+")) }.toSet() + "ds_session_id"
        for (name in names) for (path in paths) {
            val domains = listOf("", "; Domain=chat.deepseek.com", "; Domain=.chat.deepseek.com") +
                if (name == "ds_session_id") listOf("; Domain=.deepseek.com") else emptyList()
            for (domain in domains) suspendCancellableCoroutine<Unit> { continuation ->
                manager.setCookie(DeepSeekOrigin.URL, "$name=; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Path=$path$domain; Secure; HttpOnly") {
                    if (continuation.isActive) continuation.resume(Unit)
                }
            }
        }
        manager.flush()
        WebStorage.getInstance().deleteOrigin(DeepSeekOrigin.URL)
    }
}
