package app.dsandroid.deepseek

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.dsandroid.BuildConfig
import app.dsandroid.data.Appearance
import app.dsandroid.ui.AppIcon
import app.dsandroid.ui.Glyph
import app.dsandroid.ui.LocalOfficialColors
import kotlin.math.roundToInt

const val RELEASES_URL = "https://github.com/muhib-karim/deepseek-android/releases"
const val ISSUES_URL = "https://github.com/muhib-karim/deepseek-android/issues"
const val DEEPSEEK_ACCOUNT_URL = "https://chat.deepseek.com/"

/** Settings actions; the defaults keep fixtures and previews inert. */
interface SettingsActions {
    fun theme(value: Appearance) {}
    fun textScale(value: Float) {}
    fun collapseThinking(value: Boolean) {}
    fun audioLanguage(tag: String) {}
    fun appLanguage(tag: String) {}
    fun saveApiKey(value: String) {}
    fun apiFallback() {}
    fun openLink(url: String) {}
    fun deleteAllChats() {}
    fun logout() {}
    object None : SettingsActions
}

private val LANGUAGES = listOf("" to "Follow system", "en" to "English", "bn" to "বাংলা")
private val VOICE_LANGUAGES = listOf("" to "Use app language", "en-US" to "English", "bn-BD" to "বাংলা")

/**
 * Official settings (docs/reference/SCREEN_SPEC.md "settings"): white groups on a light-grey page, Profile, App, Audio and
 * About, then Log out and the AI notice. Agent and Advanced (the API-key client) are this app's additions.
 */
@Composable
internal fun SettingsPage(state: AccountUiState, actions: SettingsActions, appLanguage: String, onNavigate: (AccountPage) -> Unit, modifier: Modifier) {
    var dialog by rememberSaveable { mutableStateOf<String?>(null) }
    var apiKey by remember { mutableStateOf("") }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(modifier.fillMaxWidth().background(LocalOfficialColors.current.groupedBackground).verticalScroll(rememberScrollState())
        .padding(horizontal = 16.dp, vertical = 8.dp).testTag("settings-page"), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SettingsGroup("Profile") {
            SettingsRow("Account settings", value = state.profileEmail ?: state.profilePhone, tag = "settings-account") { onNavigate(AccountPage.PROFILE) }
            SettingsRow("Accounts", value = "${state.accounts.size}", tag = "manage-accounts", enabled = !state.busy) { onNavigate(AccountPage.ACCOUNTS) }
            SettingsRow("Data controls", tag = "settings-data") { onNavigate(AccountPage.DATA_CONTROLS) }
        }
        SettingsGroup("App") {
            SettingsRow("Language", value = LANGUAGES.firstOrNull { it.first == appLanguage }?.second ?: appLanguage, tag = "settings-language") { dialog = "language" }
            SettingsRow("Appearance", value = appearanceLabel(state.appearance), tag = "settings-appearance") { dialog = "appearance" }
            SettingsRow("Font size", value = "${(state.textScale * 100).roundToInt()}%", tag = "settings-font") { dialog = "font" }
            SettingsSwitch("Collapse thinking automatically", state.collapseThinking, "settings-collapse") { actions.collapseThinking(it) }
        }
        SettingsGroup("Audio") {
            SettingsRow("Main language", value = VOICE_LANGUAGES.firstOrNull { it.first == state.audioLanguage }?.second ?: state.audioLanguage,
                tag = "settings-voice-language") { dialog = "voice" }
        }
        SettingsGroup("Agent") {
            SettingsRow("Skills and Python toolsets", tag = "settings-skills") { onNavigate(AccountPage.SKILLS) }
            Text("Agent mode lets DeepSeek use tools on this phone: files, PDFs and documents, Python, skills and web pages. Downloads and network access ask first.",
                Modifier.padding(horizontal = 16.dp, vertical = 10.dp), style = MaterialTheme.typography.bodySmall, color = muted)
        }
        SettingsGroup("Advanced") {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("API-key mode", style = MaterialTheme.typography.bodyLarge)
                Text("Uses the separate API chat client and may use paid API credits. You can return to your DeepSeek account at any time.",
                    style = MaterialTheme.typography.bodySmall, color = muted)
                OutlinedTextField(apiKey, { apiKey = it }, label = { Text("DeepSeek API key") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth().testTag("fallback-key"))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { actions.saveApiKey(apiKey); apiKey = "" }, enabled = !state.busy && apiKey.isNotBlank()) { Text("Save API key") }
                    if (state.hasApiKey) TextButton(onClick = { apiKey = ""; actions.saveApiKey("") }, enabled = !state.busy) { Text("Clear API key") }
                }
                if (state.hasApiKey) Text("API key saved securely", style = MaterialTheme.typography.bodySmall, color = muted)
                OutlinedButton(onClick = actions::apiFallback, enabled = !state.busy, shape = RoundedCornerShape(20.dp)) { Text("API-key fallback") }
            }
        }
        SettingsGroup("About") {
            SettingsRow("Check for updates", value = "v${BuildConfig.VERSION_NAME}", tag = "settings-updates") { actions.openLink(RELEASES_URL) }
            SettingsRow("Service agreement", tag = "settings-agreement") { dialog = "agreement" }
            SettingsRow("Help & Feedback", tag = "settings-help") { actions.openLink(ISSUES_URL) }
            Text("DeepSeek Android · ${BuildConfig.VERSION_NAME}\nUnofficial client. Not affiliated with or endorsed by DeepSeek. Web sign-in and chat use DeepSeek's web protocol, which may change.",
                Modifier.padding(horizontal = 16.dp, vertical = 12.dp), style = MaterialTheme.typography.bodySmall, color = muted)
        }
        LogoutRow(state) { dialog = "logout" }
        Text("AI-generated, for reference only.", Modifier.fillMaxWidth().padding(bottom = 16.dp), textAlign = TextAlign.Center,
            style = MaterialTheme.typography.labelSmall, color = muted)
    }
    when (dialog) {
        "appearance" -> ChoiceDialog("Appearance", Appearance.entries.map { it.name to appearanceLabel(it) }, state.appearance.name,
            onConfirm = { actions.theme(Appearance.valueOf(it)); dialog = null }, onDismiss = { dialog = null })
        "language" -> ChoiceDialog("Language", LANGUAGES, appLanguage, onConfirm = { actions.appLanguage(it); dialog = null }, onDismiss = { dialog = null },
            note = "Chat and account screens are in English; বাংলা also applies to the API-key client.")
        "voice" -> ChoiceDialog("Main language", VOICE_LANGUAGES, state.audioLanguage, onConfirm = { actions.audioLanguage(it); dialog = null },
            onDismiss = { dialog = null }, note = "The primary language you speak when dictating, for better recognition.")
        "font" -> FontSizeDialog(state.textScale, onConfirm = { actions.textScale(it); dialog = null }, onDismiss = { dialog = null })
        "agreement" -> AlertDialog(onDismissRequest = { dialog = null }, title = { Text("Service agreement") },
            text = { Text("Your DeepSeek account is governed by DeepSeek's Terms of Use and Privacy Policy. This app is an unofficial, open-source client under the MIT license.") },
            confirmButton = { TextButton(onClick = { actions.openLink(TERMS_URL) }) { Text("Terms of Use") } },
            dismissButton = { TextButton(onClick = { actions.openLink(PRIVACY_URL) }) { Text("Privacy Policy") } })
        "logout" -> LogoutDialog(onConfirm = { dialog = null; actions.logout() }, onDismiss = { dialog = null })
    }
}

/**
 * Settings > Account settings. Shows what DeepSeek returns for the signed-in account; server-side actions (password,
 * other devices, deleting the account) link to DeepSeek's own site instead of pretending to happen here.
 */
@Composable
internal fun ProfilePage(state: AccountUiState, actions: SettingsActions, modifier: Modifier) {
    var logout by remember { mutableStateOf(false) }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(modifier.fillMaxWidth().background(LocalOfficialColors.current.groupedBackground).verticalScroll(rememberScrollState())
        .padding(horizontal = 16.dp, vertical = 8.dp).testTag("profile-page"), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SettingsGroup("Profile") {
            SettingsRow("Email", value = state.profileEmail ?: "Not shown by DeepSeek", tag = "profile-email")
            state.profilePhone?.let { SettingsRow("Phone number", value = it, tag = "profile-phone") }
            SettingsRow("Signed in with", value = "DeepSeek web sign-in")
            state.accounts.firstOrNull { it.active }?.let { SettingsRow("Account in this app", value = it.label) }
        }
        SettingsGroup("On DeepSeek") {
            SettingsRow("Manage your account on DeepSeek", tag = "profile-manage") { actions.openLink(DEEPSEEK_ACCOUNT_URL) }
            Text("Changing your password, logging out of other devices and deleting your account happen on DeepSeek's site; this app does not do them.",
                Modifier.padding(horizontal = 16.dp, vertical = 10.dp), style = MaterialTheme.typography.bodySmall, color = muted)
        }
        LogoutRow(state) { logout = true }
    }
    if (logout) LogoutDialog(onConfirm = { logout = false; actions.logout() }, onDismiss = { logout = false })
}

/** Settings > Data controls: where chats live, and Delete all chats (on DeepSeek and on this phone, after confirming). */
@Composable
internal fun DataControlsPage(state: AccountUiState, actions: SettingsActions, modifier: Modifier) {
    var confirm by remember { mutableStateOf(false) }
    Column(modifier.fillMaxWidth().background(LocalOfficialColors.current.groupedBackground).verticalScroll(rememberScrollState())
        .padding(horizontal = 16.dp, vertical = 8.dp).testTag("data-controls-page"), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SettingsGroup("Chats") {
            Text("Your chats are kept on DeepSeek and in this app's private storage on this phone. Files the agent makes stay on this phone until you delete their chat.",
                Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SettingsRow("Delete all chats", tag = "delete-all-chats", destructive = true, enabled = !state.busy && state.history.isNotEmpty()) { confirm = true }
        }
    }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text("Delete all chats?") },
        text = { Text("Once deleted, all chats are cleared on DeepSeek and on this phone and can't be recovered.") },
        confirmButton = { TextButton(onClick = { confirm = false; actions.deleteAllChats() }, modifier = Modifier.testTag("confirm-delete-all")) {
            Text("Delete all", color = LocalOfficialColors.current.destructive)
        } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } })
}

@Composable
private fun LogoutRow(state: AccountUiState, onClick: () -> Unit) {
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), color = LocalOfficialColors.current.groupSurface) {
        TextButton(onClick = onClick, enabled = !state.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
            Text(if (state.logoutPending) "Retry logout" else "Log out", color = LocalOfficialColors.current.destructive, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun LogoutDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Confirm log out?") },
        text = { Text("This removes this app's DeepSeek session and its local chat history and files on this phone. Your chats on DeepSeek stay; sign in again to see them.") },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Log out", color = LocalOfficialColors.current.destructive) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@Composable
internal fun SettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, Modifier.padding(start = 16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), color = LocalOfficialColors.current.groupSurface) {
            Column(content = content)
        }
    }
}

@Composable
internal fun SettingsRow(label: String, value: String? = null, tag: String? = null, enabled: Boolean = true, destructive: Boolean = false,
    onClick: (() -> Unit)? = null) {
    val base = Modifier.fillMaxWidth().heightIn(min = 52.dp)
    Row((if (onClick != null) base.clickable(enabled = enabled, onClick = onClick) else base).padding(horizontal = 16.dp)
        .then(if (tag != null) Modifier.testTag(tag) else Modifier), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge,
            color = if (destructive) LocalOfficialColors.current.destructive else MaterialTheme.colorScheme.onSurface)
        value?.let { Text(it, Modifier.widthIn(max = 180.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium,
            maxLines = 1, overflow = TextOverflow.Ellipsis) }
        if (onClick != null && !destructive) AppIcon(Glyph.CHEVRON, modifier = Modifier.size(16.dp).rotate(-90f), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SettingsSwitch(label: String, checked: Boolean, tag: String, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable { onChange(!checked) }.padding(horizontal = 16.dp).testTag(tag),
        verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** Official settings dialog: title, radio list, blue Confirm. */
@Composable
private fun ChoiceDialog(title: String, options: List<Pair<String, String>>, selected: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit,
    note: String? = null) {
    var choice by remember { mutableStateOf(selected) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) },
        text = {
            Column {
                options.forEach { (key, label) ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).selectable(choice == key, role = Role.RadioButton) { choice = key }
                        .heightIn(min = 48.dp).testTag("choice-${key.ifEmpty { "default" }}"), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = choice == key, onClick = null)
                        Text(label, Modifier.padding(start = 12.dp))
                    }
                }
                note?.let { Text(it, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        },
        confirmButton = { Button(onClick = { onConfirm(choice) }, shape = RoundedCornerShape(20.dp), modifier = Modifier.testTag("choice-confirm")) { Text("Confirm") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

/** Font size: a live preview and a slider (85 % to 130 % of the system size). */
@Composable
private fun FontSizeDialog(current: Float, onConfirm: (Float) -> Unit, onDismiss: () -> Unit) {
    var value by remember { mutableFloatStateOf(current) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Font size") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Preview text size", fontSize = (15 * value).sp, fontWeight = FontWeight.Medium)
                        Text("Drag the slider to change the size of chat text.", fontSize = (15 * value).sp, lineHeight = (22 * value).sp)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("A", fontSize = 13.sp)
                    Slider(value, { value = it }, Modifier.weight(1f).padding(horizontal = 8.dp).testTag("font-slider"), valueRange = 0.85f..1.3f, steps = 8)
                    Text("A", fontSize = 20.sp)
                }
                Text("${(value * 100).roundToInt()}%", Modifier.fillMaxWidth(), textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { Button(onClick = { onConfirm(value) }, shape = RoundedCornerShape(20.dp), modifier = Modifier.testTag("font-confirm")) { Text("Confirm") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

private fun appearanceLabel(value: Appearance) = when (value) { Appearance.SYSTEM -> "System"; Appearance.LIGHT -> "Light"; Appearance.DARK -> "Dark" }
