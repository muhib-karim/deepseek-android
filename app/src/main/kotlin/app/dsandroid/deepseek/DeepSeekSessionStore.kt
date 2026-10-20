package app.dsandroid.deepseek

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebStorage
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import app.dsandroid.core.accounts.AccountRegistry
import app.dsandroid.core.accounts.BalanceReading
import app.dsandroid.core.deepseek.DeepSeekOrigin
import app.dsandroid.core.deepseek.SessionToken
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * Independent of the legacy API settings and excluded from Android backups by the manifest. Each DeepSeek account
 * has its own session slot; the first account keeps the original "session" key. The account list and balance
 * readings live here too, so everything about signed-in accounts is in one Keystore-encrypted file.
 */
@Suppress("DEPRECATION")
class DeepSeekSessionStore(context: Context) {
    private val preferences = EncryptedSharedPreferences.create(
        context.applicationContext, "deepseek_web_session",
        MasterKey.Builder(context.applicationContext).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )
    private val json = Json { ignoreUnknownKeys = true }
    private fun key(slot: String): String {
        require(slot.matches(Regex("[A-Za-z0-9_-]{1,100}"))) { "Invalid account slot" }
        return if (slot == DEFAULT_SLOT) "session" else "session_$slot"
    }

    fun load(): SessionToken? = load(DEFAULT_SLOT)
    fun save(token: SessionToken) = save(DEFAULT_SLOT, token)
    fun load(slot: String): SessionToken? = SessionToken.parse(preferences.getString(key(slot), null))
    fun save(slot: String, token: SessionToken) {
        check(preferences.edit().putString(key(slot), token.bearer()).commit()) { "Could not securely save the DeepSeek session." }
    }
    fun remove(slot: String) { check(preferences.edit().remove(key(slot)).commit()) { "Could not clear the saved DeepSeek session." } }

    /** Null when no account list was saved yet (an install from before v0.7.0). */
    fun registry(): AccountRegistry? = preferences.getString("accounts", null)?.let { json.decodeFromString<AccountRegistry>(it) }
    fun saveRegistry(registry: AccountRegistry) {
        check(preferences.edit().putString("accounts", json.encodeToString(registry)).commit()) { "Could not securely save the account list." }
    }
    fun balances(): Map<String, BalanceReading> =
        runCatching { json.decodeFromString<Map<String, BalanceReading>>(preferences.getString("balances", "{}")!!) }.getOrDefault(emptyMap())
    fun saveBalances(readings: Map<String, BalanceReading>) {
        check(preferences.edit().putString("balances", json.encodeToString(readings)).commit()) { "Could not save balance readings." }
    }

    /** Removes every DeepSeek session, the account list and balance readings. */
    fun clear() { check(preferences.edit().clear().commit()) { "Could not clear the saved DeepSeek session." } }

    companion object {
        /** The first DeepSeek account: legacy session key, deepseek-web-chats.json and workspace/account. */
        const val DEFAULT_SLOT = "default"
    }
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
