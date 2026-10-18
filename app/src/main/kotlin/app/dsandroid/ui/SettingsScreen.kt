package app.dsandroid.ui

import androidx.appcompat.app.AppCompatDelegate
import androidx.activity.compose.BackHandler
import androidx.core.os.LocaleListCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import app.dsandroid.R
import app.dsandroid.data.AppSettings
import app.dsandroid.data.Appearance
import app.dsandroid.data.Provider

@Composable
fun SettingsScreen(settings: AppSettings, saving: Boolean, modifier: Modifier,
    save: (AppSettings) -> Unit, signIn: (AppSettings) -> Unit, signOut: () -> Unit,
    clearHistory: () -> Unit = {}, state: ChatUiState, vm: ChatViewModel) {
    // Credentials are deliberately excluded from saved-instance-state bundles.
    var draft by remember { mutableStateOf(settings) }
    var voice by remember { mutableStateOf(false) }
    var clearConfirm by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val version = remember { runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty() }.getOrDefault("") }
    var page by remember { mutableStateOf("") }
    var displayedSettings by remember { mutableStateOf(settings) }
    LaunchedEffect(settings) {
        val accountChanged = displayedSettings.runIdentity != settings.runIdentity ||
            displayedSettings.apiKey != settings.apiKey || displayedSettings.accountToken != settings.accountToken
        draft = draft.copy(connectors = settings.connectors, accounts = settings.accounts, activeAccountId = settings.activeAccountId, cachedModels = settings.cachedModels, modelCacheKey = settings.modelCacheKey,
            accountToken = settings.accountToken, accountOrigin = settings.accountOrigin, accountBinding = settings.accountBinding)
        if (accountChanged) draft = draft.copy(provider = settings.provider, baseUrl = settings.baseUrl,
            apiKey = settings.apiKey, customKey = settings.customKey, model = settings.model)
        if (page == "backup") draft = settings
        displayedSettings = settings
    }
    var dialog by remember { mutableStateOf("") }
    var pendingLanguage by remember { mutableStateOf("") }
    var pendingAppearance by remember { mutableStateOf(draft.appearance) }
    var pendingFont by remember { mutableStateOf(draft.fontScale) }
    val locale = AppCompatDelegate.getApplicationLocales().toLanguageTags()
    BackHandler(page.isNotEmpty()) { page = "" }
    if(page.isNotEmpty()) {
        Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant).verticalScroll(rememberScrollState()).imePadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            TextButton(onClick = { page = "" }) { Text(stringResource(R.string.back)) }
            Text(stringResource(when(page) { "diagnostics" -> R.string.diagnostics; "backup" -> R.string.backup; "account" -> R.string.account; "data" -> R.string.data_management; "memory" -> R.string.memory; "tools" -> R.string.tools; "connectors" -> R.string.connectors; else -> R.string.personalization }), style = MaterialTheme.typography.headlineSmall)
            SettingsGroup {
                when(page) {
                    "backup" -> BackupSettingsPanel(state, vm)
                    "diagnostics" -> DiagnosticsPanel(state.diagnostics, vm)
                    "account" -> {
                AccountPicker(settings, saving || state.running || state.signingIn, vm)
                Text(stringResource(R.string.provider_notice), style = MaterialTheme.typography.bodySmall)
                Provider.entries.forEach { provider ->
                    val label = stringResource(when(provider) { Provider.ACCOUNT -> R.string.provider_account; Provider.API_KEY -> R.string.provider_key; Provider.CUSTOM -> R.string.provider_custom })
                    FilterChip(selected = draft.provider == provider, onClick = { draft = draft.copy(provider = provider) }, label = { Text(label) })
                }
                SettingField(stringResource(R.string.base_url), draft.baseUrl) { draft = draft.copy(baseUrl = it) }
                Text(stringResource(R.string.local_http_notice), style = MaterialTheme.typography.bodySmall)
                SettingField(stringResource(R.string.model_override), draft.model) { draft = draft.copy(model = it) }
                when(draft.provider) {
                    Provider.API_KEY -> SettingField(stringResource(R.string.api_key), draft.apiKey, true) { draft = draft.copy(apiKey = it) }
                    Provider.CUSTOM -> SettingField(stringResource(R.string.custom_key), draft.customKey, true) { draft = draft.copy(customKey = it) }
                    Provider.ACCOUNT -> {
                        Text(stringResource(R.string.pkce_notice), style = MaterialTheme.typography.bodySmall)
                        SettingField(stringResource(R.string.authorize_url), draft.authorizeUrl) { draft = draft.copy(authorizeUrl = it) }
                        SettingField(stringResource(R.string.exchange_url), draft.exchangeUrl) { draft = draft.copy(exchangeUrl = it) }
                        SettingField(stringResource(R.string.client_id), draft.clientId) { draft = draft.copy(clientId = it) }
                        if (!draft.accountConfigured) Text(stringResource(R.string.account_unconfigured), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Button(onClick = { signIn(draft) }, enabled = !saving && draft.accountConfigured) { Text(stringResource(R.string.save_sign_in)) }
                    }
                }
                Text(stringResource(R.string.capabilities_notice), style = MaterialTheme.typography.bodySmall)
                val modelId = draft.selectedModel
                val flags = draft.capabilities[modelId] ?: app.dsandroid.core.llm.ModelCapabilities()
                CapabilitySetting(stringResource(R.string.images), flags.vision) { flag -> draft = draft.copy(capabilities = draft.capabilities + (modelId to flags.copy(vision = flag))) }
                CapabilitySetting(stringResource(R.string.reasoning_model), flags.reasoning) { flag -> draft = draft.copy(capabilities = draft.capabilities + (modelId to flags.copy(reasoning = flag))) }
                    }
                    "memory" -> {
                        MemorySettings(state.facts, draft.memoryEnabled, saving || state.personalizing,
                            { draft = draft.copy(memoryEnabled = it) }, vm::saveFact, vm::deleteFact)
                        MemoryUpkeepSettings(draft.memoryUpkeep, { draft = draft.copy(memoryUpkeep = it) }, state, vm)
                    }
                    "connectors" -> ConnectorsSettings(settings, state, vm)
                    "tools" -> ToolsSettings(draft, remember { vm.builtInToolNames() }, saving) { draft = it }
                    "data" -> {
                        Text(stringResource(R.string.local_data_notice), style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = { clearConfirm = true }, enabled = !saving) { Text(stringResource(R.string.clear_history)) }
                    }
                    else -> {
                        OutlinedTextField(draft.customInstructions, { draft = draft.copy(customInstructions = it.take(8000)) },
                            label = { Text(stringResource(R.string.global_instructions)) }, modifier = Modifier.fillMaxWidth(), minLines = 3, maxLines = 10)
                        Text(stringResource(R.string.global_instructions_notice), style = MaterialTheme.typography.bodySmall)
                        SettingToggle(stringResource(R.string.suggested_follow_ups), draft.suggestedFollowUps) { draft = draft.copy(suggestedFollowUps = it) }
                        SettingToggle(stringResource(R.string.collapse_thinking), draft.collapseThinking) { draft = draft.copy(collapseThinking = it) }
                        Text(stringResource(R.string.collapse_thinking_help), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            if (page !in setOf("backup", "diagnostics")) Button(onClick = { save(draft) }, enabled = !saving, modifier = Modifier.fillMaxWidth()) { Text(stringResource(if(saving) R.string.saving else R.string.save_settings)) }
        }
    } else Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant)
        .verticalScroll(rememberScrollState()).imePadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(stringResource(R.string.profile), color = MaterialTheme.colorScheme.onSurfaceVariant)
        SettingsGroup {
            SettingsRow(stringResource(R.string.account), stringResource(if(settings.accountToken.isNotEmpty()) R.string.account_saved else R.string.local_profile)) { page = "account" }
            HorizontalDivider()
            SettingsRow(stringResource(R.string.data_management), "") { page = "data" }
            SettingsRow(stringResource(R.string.backup), "") { page = "backup" }
            SettingsRow(stringResource(R.string.diagnostics), "") { page = "diagnostics" }
        }
        Text(stringResource(R.string.app_section), color = MaterialTheme.colorScheme.onSurfaceVariant)
        SettingsGroup {
            SettingsRow(stringResource(R.string.app_language), stringResource(if(locale.startsWith("en")) R.string.english else if(locale.startsWith("bn")) R.string.bangla else R.string.follow_system)) { pendingLanguage = locale; dialog = "language" }
            HorizontalDivider()
            SettingsRow(stringResource(R.string.appearance), stringResource(when(draft.appearance) { Appearance.SYSTEM -> R.string.follow_system; Appearance.LIGHT -> R.string.light; Appearance.DARK -> R.string.dark })) { pendingAppearance = draft.appearance; dialog = "appearance" }
            HorizontalDivider()
            SettingsRow(stringResource(R.string.font_size), stringResource(R.string.font_percent, (draft.fontScale * 100).toInt())) { pendingFont = draft.fontScale; dialog = "font" }
            HorizontalDivider()
            SettingsRow(stringResource(R.string.personalization), "") { page = "personalization" }
            HorizontalDivider()
            SettingsRow(stringResource(R.string.connectors), "") { page = "connectors" }
            SettingsRow(stringResource(R.string.memory), "") { page = "memory" }
            HorizontalDivider()
            SettingsRow(stringResource(R.string.tools), "") { page = "tools" }
        }
        Text(stringResource(R.string.audio), color = MaterialTheme.colorScheme.onSurfaceVariant)
        SettingsGroup {
            SettingsRow(stringResource(R.string.audio_language), stringResource(if(draft.audioLanguage.isEmpty()) R.string.use_app_language else if(draft.audioLanguage.startsWith("bn")) R.string.bangla else R.string.english)) { pendingLanguage = draft.audioLanguage; dialog = "audio" }
            HorizontalDivider()
            SettingsRow(stringResource(R.string.voice), stringResource(R.string.device_speech_service)) { voice = true }
        }
        Text(stringResource(R.string.dictation_notice), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(stringResource(R.string.search_backend), color = MaterialTheme.colorScheme.onSurfaceVariant)
        SettingsGroup {
            ChoiceRow(stringResource(R.string.duckduckgo), draft.searchBackend == "duckduckgo") { draft = draft.copy(searchBackend = "duckduckgo") }
            ChoiceRow(stringResource(R.string.searxng), draft.searchBackend == "searxng") { draft = draft.copy(searchBackend = "searxng") }
            if(draft.searchBackend == "searxng") SettingField(stringResource(R.string.searxng_url), draft.searxngUrl) { draft = draft.copy(searxngUrl = it) }
        }
        Text(stringResource(R.string.about), color = MaterialTheme.colorScheme.onSurfaceVariant)
        SettingsGroup {
            SettingsRow(stringResource(R.string.check_updates), version) { dialog = "updates" }
            HorizontalDivider()
            SettingsRow(stringResource(R.string.service), "") { dialog = "service" }
            HorizontalDivider()
            SettingsRow(stringResource(R.string.help), "") { dialog = "help" }
        }
        Button(onClick = { save(draft) }, enabled = !saving, modifier = Modifier.fillMaxWidth()) { Text(stringResource(if(saving) R.string.saving else R.string.save_settings)) }
        TextButton(onClick = signOut, enabled = !saving, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.log_out_local)) }
        Text(stringResource(R.string.unofficial), style = MaterialTheme.typography.bodySmall)
        Text(stringResource(R.string.ai_generated_footer), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
    }
    if(dialog.isNotEmpty()) AlertDialog(onDismissRequest = { dialog = "" },
        title = { Text(stringResource(when(dialog) { "language" -> R.string.app_language; "appearance" -> R.string.appearance; "audio" -> R.string.audio_language; "font" -> R.string.font_size; "updates" -> R.string.check_updates; "service" -> R.string.service; else -> R.string.help })) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                when(dialog) {
                    "language", "audio" -> {
                        ChoiceRow(stringResource(if(dialog == "audio") R.string.use_app_language else R.string.follow_system), pendingLanguage.isEmpty()) { pendingLanguage = "" }
                        ChoiceRow(stringResource(R.string.english), pendingLanguage.startsWith("en")) { pendingLanguage = if(dialog == "audio") "en-US" else "en" }
                        ChoiceRow(stringResource(R.string.bangla), pendingLanguage.startsWith("bn")) { pendingLanguage = if(dialog == "audio") "bn-BD" else "bn" }
                    }
                    "appearance" -> Appearance.entries.forEach { appearance -> ChoiceRow(stringResource(when(appearance) { Appearance.SYSTEM -> R.string.follow_system; Appearance.LIGHT -> R.string.light; Appearance.DARK -> R.string.dark }), pendingAppearance == appearance) { pendingAppearance = appearance } }
                    "font" -> { Slider(value = pendingFont, onValueChange = { pendingFont = it }, valueRange = 0.85f..1.3f, steps = 8); Text(stringResource(R.string.font_sample), style = MaterialTheme.typography.bodyMedium.copy(fontSize = MaterialTheme.typography.bodyMedium.fontSize * pendingFont)) }
                    "updates" -> Text(stringResource(R.string.updates_notice, version))
                    "service" -> { Text(stringResource(R.string.about_provider)); Text(stringResource(R.string.unofficial)) }
                    else -> Text(stringResource(R.string.help_text))
                }
            }
        },
        confirmButton = { TextButton(onClick = {
            when(dialog) {
                "language" -> AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(pendingLanguage))
                "appearance" -> draft = draft.copy(appearance = pendingAppearance)
                "audio" -> draft = draft.copy(audioLanguage = pendingLanguage)
                "font" -> draft = draft.copy(fontScale = pendingFont)
            }
            dialog = ""
        }) { Text(stringResource(R.string.confirm)) } })
    if(voice) VoiceChooser { voice = false }

    if (clearConfirm) AlertDialog(onDismissRequest = { clearConfirm = false }, title = { Text(stringResource(R.string.clear_history)) }, text = { Text(stringResource(R.string.clear_history_notice)) },
        confirmButton = { TextButton(onClick = { clearConfirm = false; clearHistory() }) { Text(stringResource(R.string.delete)) } }, dismissButton = { TextButton(onClick = { clearConfirm = false }) { Text(stringResource(R.string.cancel)) } })
}

@Composable private fun SettingsGroup(content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}
@Composable private fun SettingsRow(label: String, value: String, click: () -> Unit) {
    Surface(onClick = click, color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        Row(Modifier.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(label, Modifier.weight(1f))
            if(value.isNotEmpty()) Text(value, Modifier.widthIn(max = 140.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            AppIcon(Glyph.CHEVRON, modifier = Modifier.size(16.dp))
        }
    }
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun VoiceChooser(dismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = dismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(R.string.voice), style = MaterialTheme.typography.titleLarge)
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.device_speech_service), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.voice_notice))
                }
            }
            Button(onClick = dismiss, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.confirm)) }
        }
    }
}
@Composable private fun ChoiceRow(label: String, selected: Boolean, click: () -> Unit) {
    Surface(onClick = click, color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) { Row(verticalAlignment = Alignment.CenterVertically) { Text(label, Modifier.weight(1f)); RadioButton(selected, onClick = click) } }
}
@Composable private fun SettingField(label: String, value: String, secret: Boolean = false, update: (String) -> Unit) {
    OutlinedTextField(value, update, label = { Text(label) }, modifier = Modifier.fillMaxWidth(), singleLine = true,
        visualTransformation = if(secret) PasswordVisualTransformation() else VisualTransformation.None)
}
@Composable private fun SettingToggle(label: String, checked: Boolean, update: (Boolean) -> Unit) { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text(label, Modifier.weight(1f)); Switch(checked, update) } }
@Composable private fun CapabilitySetting(label: String, value: Boolean?, update: (Boolean?) -> Unit) {
    Text(label, style = MaterialTheme.typography.labelLarge)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf(null to R.string.auto, true to R.string.yes, false to R.string.no).forEach { (flag,title) -> FilterChip(selected = value == flag, onClick = { update(flag) }, label = { Text(stringResource(title)) }) } }
}
@Composable
fun SignInScreen(settings: AppSettings, state: ChatUiState, modifier: Modifier, signIn: () -> Unit, cancel: () -> Unit) {
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(stringResource(R.string.sign_in_browser), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.sign_in_privacy))
        if(!settings.accountConfigured) Text(stringResource(R.string.account_unconfigured))
        Text(stringResource(R.string.sign_in_quota))
        state.signInMessage?.let { Text(it) }
        if(state.signingIn) { LinearProgressIndicator(Modifier.fillMaxWidth()); OutlinedButton(onClick = cancel) { Text(stringResource(R.string.cancel_sign_in)) } }
        else Button(onClick = signIn, enabled = settings.accountConfigured) { Text(stringResource(R.string.continue_browser)) }
    }
}
