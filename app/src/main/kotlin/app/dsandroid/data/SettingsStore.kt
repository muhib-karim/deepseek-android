package app.dsandroid.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import app.dsandroid.core.accounts.*
import app.dsandroid.core.guard.GuardSettings
import app.dsandroid.core.mcp.RemoteServer
import app.dsandroid.core.llm.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString

enum class Provider(val label: String) {
    ACCOUNT("DeepSeek account"), API_KEY("API key"), CUSTOM("Custom OpenAI-compatible")
}

enum class Appearance { SYSTEM, LIGHT, DARK }

data class AppSettings(
    val connectors: List<app.dsandroid.core.connectors.ConnectorConfig> = emptyList(),
    val accounts: List<AccountProfile> = emptyList(),
    val activeAccountId: String? = null,
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
    val accountBinding: String = "",
    val shellEnabled: Boolean = false,
    val installEnabled: Boolean = false,
    val authorizeUrl: String = "",
    val exchangeUrl: String = "",
    val clientId: String = "",
    val appearance: Appearance = Appearance.SYSTEM,
    val fontScale: Float = 1f,
    val collapseThinking: Boolean = true,
    val suggestedFollowUps: Boolean = true,
    val audioLanguage: String = "",
    val searchBackend: String = "duckduckgo",
    val searxngUrl: String = "",
    val memoryEnabled: Boolean = true,
    val memoryUpkeep: Boolean = false,
    val customInstructions: String = "",
    val disabledTools: Set<String> = emptySet(),
    val remoteServers: List<RemoteServer> = emptyList(),
    /**
     * Plugins & tools > Trusted execution: run_python, pip_install and skill functions run native code with this app's rights,
     * so agents are offered them only when this is on, and each call still asks. Off by default; never part of a backup.
     */
    val trustedExecution: Boolean = false,
    /** Settings > Agent > Maximum steps: assistant steps one reply may take (1-20, default 8), on both routes. */
    val agentMaxSteps: Int = app.dsandroid.core.agent.AgentLoop.DEFAULT_MAX_STEPS,
) {
    val accountConfigured: Boolean get() = authorizeUrl.isNotBlank() && exchangeUrl.isNotBlank() && clientId.isNotBlank()
    fun guardSettings() = GuardSettings(shellEnabled, installEnabled)
    val cacheKey: String get() = "${provider.name}:${baseUrl.trimEnd('/')}"
    val runIdentity: String get() = "$cacheKey:$accountBinding"
    val availableModels: List<ModelInfo> get() = cachedModels.takeIf { modelCacheKey == cacheKey }.orEmpty()
    val selectedModel: String get() = ModelSelector.selected(availableModels, model)
    fun turnModel(think: Boolean): String = ModelSelector.forTurn(availableModels, model, think, capabilities)
    /** API mode's model for a turn: deepseek-chat whenever tools are offered (Agent on), see [ModelSelector.forAgentTurn]. */
    fun turnModel(think: Boolean, tools: Boolean): String = ModelSelector.forAgentTurn(availableModels, model, think, tools, capabilities)
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
    private fun accountBinding(): String = preferences.getString("account_binding", null)?.takeIf { it.isNotBlank() }
        ?: java.util.UUID.randomUUID().toString().also { check(preferences.edit().putString("account_binding", it).commit()) { "Could not save account binding" } }

    fun load(): AppSettings = AppSettings(
        connectors = LlmJson.decodeFromString<List<app.dsandroid.core.connectors.ConnectorConfig>>(preferences.getString("connectors", "[]")!!),
        accounts = LlmJson.decodeFromString<List<AccountProfile>>(preferences.getString("accounts", "[]")!!),
        activeAccountId = preferences.getString("active_account_id", null),
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
        accountBinding = accountBinding(),
        shellEnabled = preferences.getBoolean("shell_enabled", false),
        installEnabled = preferences.getBoolean("install_enabled", false),
        authorizeUrl = preferences.getString("authorize_url", "")!!,
        exchangeUrl = preferences.getString("exchange_url", "")!!,
        clientId = preferences.getString("client_id", "")!!,
        appearance = runCatching { Appearance.valueOf(preferences.getString("appearance", "SYSTEM")!!) }.getOrDefault(Appearance.SYSTEM),
        fontScale = preferences.getFloat("font_scale", 1f).coerceIn(0.85f, 1.3f),
        collapseThinking = preferences.getBoolean("collapse_thinking", true),
        suggestedFollowUps = preferences.getBoolean("suggested_follow_ups", true),
        audioLanguage = preferences.getString("audio_language", "")!!,
        searchBackend = preferences.getString("search_backend", "duckduckgo")!!,
        searxngUrl = preferences.getString("searxng_url", "")!!,
        memoryUpkeep = preferences.getBoolean("memory_upkeep", false),
        memoryEnabled = preferences.getBoolean("memory_enabled", true),
        customInstructions = preferences.getString("custom_instructions", "")!!.take(8000),
        disabledTools = preferences.getStringSet("disabled_tools", emptySet())!!.toSet(),
        remoteServers = runCatching { LlmJson.decodeFromString<List<RemoteServer>>(preferences.getString("remote_servers", "[]")!!) }.getOrDefault(emptyList()),
        trustedExecution = preferences.getBoolean("trusted_execution", false),
        agentMaxSteps = app.dsandroid.core.agent.AgentLoop.clampSteps(preferences.getInt("agent_max_steps", app.dsandroid.core.agent.AgentLoop.DEFAULT_MAX_STEPS)),
    )

    fun connectorToken(id: String): String? = preferences.getString("connector_token_$id", null)
    fun saveConnectorToken(id: String, token: String) {
        require(id.matches(Regex("[A-Za-z0-9_-]{1,100}")) && token.isNotBlank() && token.length <= 16384)
        check(preferences.edit().putString("connector_token_$id", token).commit())
    }
    fun deleteConnectorToken(id: String) { check(preferences.edit().remove("connector_token_$id").commit()) }

    fun save(value: AppSettings) {
        require(value.connectors.size <= 8)
        require(value.connectors.map { it.id }.distinct().size == value.connectors.size)
        value.connectors.forEach { it.validate() }
        AccountProfiles(value.accounts, value.activeAccountId)
        check(preferences.edit().putString("connectors", LlmJson.encodeToString(value.connectors)).putString("accounts", LlmJson.encodeToString(value.accounts))
            .putString("active_account_id", value.activeAccountId).putString("provider", value.provider.name)
            .putString("base_url", value.baseUrl).putString("model", value.model)
            .putString("models", LlmJson.encodeToString(ModelsResponse(value.cachedModels)))
            .putString("model_cache_key", value.modelCacheKey)
            .putString("model_capabilities", LlmJson.encodeToString(value.capabilities))
            .putString("api_key", value.apiKey).putString("custom_key", value.customKey)
            .putString("account_token", value.accountToken).putString("account_origin", value.accountOrigin)
            .putString("account_binding", value.accountBinding)
            .putBoolean("shell_enabled", value.shellEnabled).putBoolean("install_enabled", value.installEnabled)
            .putString("authorize_url", value.authorizeUrl).putString("exchange_url", value.exchangeUrl)
            .putString("client_id", value.clientId)
            .putString("appearance", value.appearance.name).putFloat("font_scale", value.fontScale.coerceIn(0.85f, 1.3f))
            .putBoolean("suggested_follow_ups", value.suggestedFollowUps)
            .putBoolean("collapse_thinking", value.collapseThinking).putString("audio_language", value.audioLanguage)
            .putString("search_backend", value.searchBackend).putString("searxng_url", value.searxngUrl)
            .putBoolean("memory_upkeep", value.memoryUpkeep).putBoolean("memory_enabled", value.memoryEnabled).putString("custom_instructions", value.customInstructions.take(8000))
            .putStringSet("disabled_tools", value.disabledTools).putString("remote_servers", LlmJson.encodeToString(value.remoteServers))
            .putBoolean("trusted_execution", value.trustedExecution)
            .putInt("agent_max_steps", app.dsandroid.core.agent.AgentLoop.clampSteps(value.agentMaxSteps))
            .commit()) { "Could not save encrypted settings" }
    }
}
