package app.dsandroid.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import app.dsandroid.core.llm.ModelCapabilities
import app.dsandroid.data.AppSettings
import app.dsandroid.data.Provider

@Composable
fun SettingsScreen(settings: AppSettings, saving: Boolean, modifier: Modifier,
    save: (AppSettings) -> Unit, signIn: (AppSettings) -> Unit, signOut: () -> Unit) {
    // Deliberately not rememberSaveable: secrets must never enter saved-instance-state bundles.
    var draft by remember { mutableStateOf(settings) }
    LaunchedEffect(settings) {
        // A model refresh must not overwrite a key or endpoint currently being edited.
        // Account grants must follow explicit sign-out/sign-in immediately.
        draft = draft.copy(cachedModels = settings.cachedModels, modelCacheKey = settings.modelCacheKey,
            accountToken = settings.accountToken, accountOrigin = settings.accountOrigin)
    }
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Provider", style = MaterialTheme.typography.titleMedium)
        Provider.entries.forEach { provider ->
            FilterChip(selected = draft.provider == provider, onClick = { draft = draft.copy(provider = provider) }, label = { Text(provider.label) })
        }
        SettingField("Base URL", draft.baseUrl) { draft = draft.copy(baseUrl = it) }
        Text("Custom providers may use HTTP on a trusted local network. HTTP exposes prompts and credentials to that network.", style = MaterialTheme.typography.bodySmall)
        SettingField("Model override (blank = automatic newest)", draft.model) { draft = draft.copy(model = it) }
        Text("Automatic selection uses the provider’s model list. You can also choose a model from the chat title.", style = MaterialTheme.typography.bodySmall)
        val modelId = draft.selectedModel
        val flags = draft.capabilities[modelId] ?: ModelCapabilities()
        Text("Capabilities for $modelId", style = MaterialTheme.typography.titleSmall)
        CapabilitySetting("Images", flags.vision) { flag -> draft = draft.copy(capabilities = draft.capabilities + (modelId to flags.copy(vision = flag))) }
        CapabilitySetting("Reasoning model", flags.reasoning) { flag -> draft = draft.copy(capabilities = draft.capabilities + (modelId to flags.copy(reasoning = flag))) }
        when (draft.provider) {
            Provider.API_KEY -> SettingField("API key", draft.apiKey, secret = true) { draft = draft.copy(apiKey = it) }
            Provider.CUSTOM -> SettingField("Custom endpoint key (optional)", draft.customKey, secret = true) { draft = draft.copy(customKey = it) }
            Provider.ACCOUNT -> {
                Text(if (settings.accountToken.isNotEmpty()) "An account grant is saved on this device." else "No account grant is saved.")
                if (!draft.accountConfigured) Text("Account sign-in endpoint not configured; use an API key")
                Button(onClick = { signIn(draft) }, enabled = !saving) { Text("Save and open sign-in") }
                if (settings.accountToken.isNotEmpty()) TextButton(onClick = signOut, enabled = !saving) { Text("Remove local account grant") }
            }
        }
        HorizontalDivider()
        Text("Advanced", style = MaterialTheme.typography.titleLarge)
        Text("Account integration must be supported by the provider. No authorize URL, exchange URL or client ID is assumed.", style = MaterialTheme.typography.bodySmall)
        SettingField("Authorize URL (HTTPS)", draft.authorizeUrl) { draft = draft.copy(authorizeUrl = it) }
        SettingField("Token exchange URL (HTTPS)", draft.exchangeUrl) { draft = draft.copy(exchangeUrl = it) }
        SettingField("OAuth client ID", draft.clientId) { draft = draft.copy(clientId = it) }
        SettingToggle("Enable shell tools", draft.shellEnabled) { draft = draft.copy(shellEnabled = it) }
        SettingToggle("Enable install tools", draft.installEnabled) { draft = draft.copy(installEnabled = it) }
        Text("Warning: shell and install actions can change your device. Each action always requires confirmation. These tools are not registered in v0.1.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        Button(onClick = { save(draft) }, enabled = !saving, modifier = Modifier.fillMaxWidth()) { Text(if (saving) "Saving…" else "Save settings") }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun SettingField(label: String, value: String, secret: Boolean = false, update: (String) -> Unit) {
    OutlinedTextField(value = value, onValueChange = update, label = { Text(label) }, modifier = Modifier.fillMaxWidth(), singleLine = true,
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None)
}

@Composable
private fun SettingToggle(label: String, checked: Boolean, update: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = update)
    }
}

@Composable
fun SignInScreen(settings: AppSettings, state: ChatUiState, modifier: Modifier, signIn: () -> Unit, cancel: () -> Unit) {
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Sign in in your browser", style = MaterialTheme.typography.headlineSmall)
        Text("Your password stays on the provider’s page. DeepSeek Android receives an account grant through a local callback and stores it with Android Keystore-backed encryption.")
        if (!settings.accountConfigured) Text("Account sign-in endpoint not configured; use an API key", color = MaterialTheme.colorScheme.error)
        Text("Third-party sign-in may be unavailable or refused. Account inference may use your Platform balance or bonus; it is not a promise of unlimited free chat.")
        state.signInMessage?.let { Text(it) }
        if (state.signingIn) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            OutlinedButton(onClick = cancel) { Text("Cancel sign-in") }
        } else Button(onClick = signIn, enabled = settings.accountConfigured) { Text("Continue in browser") }
    }
}

@Composable
private fun CapabilitySetting(label: String, value: Boolean?, update: (Boolean?) -> Unit) {
    Text(label, style = MaterialTheme.typography.labelLarge)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(null to "Auto", true to "Yes", false to "No").forEach { (flag, title) ->
            FilterChip(selected = value == flag, onClick = { update(flag) }, label = { Text(title) })
        }
    }
}
