package app.dsandroid.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import app.dsandroid.core.guard.GuardSettings
import app.dsandroid.core.llm.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString

enum class Provider(val label: String) {
    ACCOUNT("DeepSeek account"), API_KEY("API key"), CUSTOM("Custom OpenAI-compatible")
}

data class AppSettings(
    val provider: Provider = Provider.API_KEY,
    val baseUrl: String = "https://api.deepseek.com",
    val model: String = "",
    val cachedModels: List<ModelInfo> = emptyList(),
    val modelCacheKey: String = "",
    val capabilities: Map<String, ModelCapabilities> = emptyMap(),
    val apiKey: String = "",
    val customKey: String = "",
    val accountToken: String = "",
    val accountOrigin: String = "",
    val shellEnabled: Boolean = false,
    val installEnabled: Boolean = false,
    val authorizeUrl: String = "",
    val exchangeUrl: String = "",
    val clientId: String = "",
) {
    val accountConfigured: Boolean get() = authorizeUrl.isNotBlank() && exchangeUrl.isNotBlank() && clientId.isNotBlank()
    fun guardSettings() = GuardSettings(shellEnabled, installEnabled)
    val cacheKey: String get() = "${provider.name}:${baseUrl.trimEnd('/')}"
    val availableModels: List<ModelInfo> get() = cachedModels.takeIf { modelCacheKey == cacheKey }.orEmpty()
    val selectedModel: String get() = ModelSelector.selected(availableModels, model)
    fun turnModel(think: Boolean): String = ModelSelector.forTurn(availableModels, model, think, capabilities)
    fun supportsVision(think: Boolean): Boolean = (capabilities[turnModel(think)] ?: ModelCapabilities()).isVision(turnModel(think))
}

@Suppress("DEPRECATION")
class SettingsStore(context: Context) {
    private val preferences = EncryptedSharedPreferences.create(
        context.applicationContext,
        "dsandroid_settings",
        MasterKey.Builder(context.applicationContext).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    fun load(): AppSettings = AppSettings(
        provider = runCatching { Provider.valueOf(preferences.getString("provider", "API_KEY")!!) }.getOrDefault(Provider.API_KEY),
        baseUrl = preferences.getString("base_url", "https://api.deepseek.com")!!,
        model = preferences.getString("model", "")!!,
        cachedModels = runCatching { LlmJson.decodeFromString<ModelsResponse>(preferences.getString("models", "{}")!!).data }.getOrDefault(emptyList()),
        modelCacheKey = preferences.getString("model_cache_key", "")!!,
        capabilities = runCatching { LlmJson.decodeFromString<Map<String, ModelCapabilities>>(preferences.getString("model_capabilities", "{}")!!) }.getOrDefault(emptyMap()),
        apiKey = preferences.getString("api_key", "")!!,
        customKey = preferences.getString("custom_key", "")!!,
        accountToken = preferences.getString("account_token", "")!!,
        accountOrigin = preferences.getString("account_origin", "")!!,
        shellEnabled = preferences.getBoolean("shell_enabled", false),
        installEnabled = preferences.getBoolean("install_enabled", false),
        authorizeUrl = preferences.getString("authorize_url", "")!!,
        exchangeUrl = preferences.getString("exchange_url", "")!!,
        clientId = preferences.getString("client_id", "")!!,
    )

    fun save(value: AppSettings) {
        check(preferences.edit().putString("provider", value.provider.name)
            .putString("base_url", value.baseUrl).putString("model", value.model)
            .putString("models", LlmJson.encodeToString(ModelsResponse(value.cachedModels)))
            .putString("model_cache_key", value.modelCacheKey)
            .putString("model_capabilities", LlmJson.encodeToString(value.capabilities))
            .putString("api_key", value.apiKey).putString("custom_key", value.customKey)
            .putString("account_token", value.accountToken).putString("account_origin", value.accountOrigin)
            .putBoolean("shell_enabled", value.shellEnabled).putBoolean("install_enabled", value.installEnabled)
            .putString("authorize_url", value.authorizeUrl).putString("exchange_url", value.exchangeUrl)
            .putString("client_id", value.clientId).commit()) { "Could not save encrypted settings" }
    }
}
