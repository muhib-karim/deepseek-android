package app.dsandroid.deepseek

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.dsandroid.core.accounts.AccountType
import app.dsandroid.core.agent.TurnStep
import app.dsandroid.core.deepseek.WebCitation
import app.dsandroid.core.editor.EditorLanguage
import app.dsandroid.core.tools.Artifact
import app.dsandroid.data.Appearance
import app.dsandroid.ui.AppIcon
import app.dsandroid.ui.DarkChatColors
import app.dsandroid.ui.DarkOfficialColors
import app.dsandroid.ui.LightChatColors
import app.dsandroid.ui.LightOfficialColors
import app.dsandroid.ui.LocalOfficialColors
import app.dsandroid.ui.OfficialTypography
import app.dsandroid.ui.Glyph
import app.dsandroid.ui.MarkdownText
import app.dsandroid.workbench.WorkbenchPane
import java.io.File
import kotlinx.coroutines.launch

enum class AccountPage { CHAT, SETTINGS, LOGIN, SKILLS, ACCOUNTS, PROFILE, DATA_CONTROLS }
/** Pages drawn on the official light-grey grouped background. */
internal val GROUPED_PAGES = setOf(AccountPage.SETTINGS, AccountPage.PROFILE, AccountPage.DATA_CONTROLS)
data class AccountMessage(
    val id: String, val user: Boolean, val text: String, val thinking: String = "", val prompt: String? = null,
    val steps: List<TurnStep> = emptyList(), val artifacts: List<Artifact> = emptyList(), val codeRuns: List<TurnStep.ToolCall> = emptyList(),
    val revisionOf: String? = null,
    /** "Thought for N seconds", as the service reported it; null for older messages or no thinking. */
    val thinkingSeconds: Double? = null,
    /** Pages a Search turn read, shown as "Read N web pages". */
    val citations: List<WebCitation> = emptyList(),
)
/** One row of Settings > Accounts: no credential, only what the list shows. */
data class AccountRow(val id: String, val label: String, val type: AccountType, val active: Boolean, val signedIn: Boolean,
    val balance: String, val lastRead: String?)
/** A file in the current chat's folder; [path] is workspace-relative. */
data class ChatFileRow(val path: String, val name: String, val bytes: Long)
/** A tool call waiting for the user's per-action approval (network, downloads). */
data class AgentConfirmation(val tool: String, val summary: String)
data class SkillRow(val name: String, val description: String, val version: String, val builtIn: Boolean, val enabled: Boolean, val tools: Int)
data class PackageRow(val name: String, val version: String, val bytes: Long)
data class SkillPreview(val name: String, val version: String, val description: String, val tools: List<String>, val pip: List<String>)

/** Agent-mode callbacks; the defaults keep fixtures and previews inert. */
interface AgentActions {
    fun setAgent(enabled: Boolean) {}
    fun runCode(messageId: String, code: String) {}
    fun exportPdf(messageId: String) {}
    fun openArtifact(artifact: Artifact) {}
    fun saveArtifact(artifact: Artifact) {}
    fun shareArtifact(artifact: Artifact) {}
    fun artifactFile(artifact: Artifact): File? = null
    fun answerTool(allow: Boolean) {}
    fun installSkillFromUrl(url: String) {}
    fun installSkillFromFile() {}
    fun answerSkillInstall(allow: Boolean) {}
    fun setSkillEnabled(name: String, enabled: Boolean) {}
    fun removeSkill(name: String) {}
    fun installPackages(specs: String) {}
    fun answerPackageInstall(allow: Boolean) {}
    fun removePackage(name: String) {}
    fun previewCode(messageId: String, revisionOf: String?, language: String, code: String) {}
    fun editCode(language: String, code: String) {}
    fun editArtifact(artifact: Artifact) {}
    fun openChatFiles() {}
    fun openChatFile(row: ChatFileRow) {}
    fun hideChatFiles() {}
    object None : AgentActions
}

/** Settings > Accounts callbacks. Only [switchTo] changes the account in use; the defaults keep fixtures inert. */
interface AccountActions {
    fun switchTo(id: String) {}
    fun addDeepSeekAccount() {}
    fun addApiKeyProfile(label: String, key: String) {}
    fun remove(id: String) {}
    fun rename(id: String, label: String) {}
    fun refreshBalances(id: String?) {}
    object None : AccountActions
}
/** A drawer row: [updatedAt] and [pinned] place it in a group; [searchText] is lowercase title and message text. */
data class AccountHistory(val id: String, val title: String, val updatedAt: Long = 0, val pinned: Boolean = false, val searchText: String = "")
data class AccountUiState(
    val ready: Boolean = false,
    val signedIn: Boolean = false,
    val hasApiKey: Boolean = false,
    val logoutPending: Boolean = false,
    val page: AccountPage = AccountPage.CHAT,
    val appearance: Appearance = Appearance.SYSTEM,
    val title: String = "New chat",
    val conversationId: String = "",
    val messages: List<AccountMessage> = emptyList(),
    val history: List<AccountHistory> = emptyList(),
    val draft: String = "",
    val busy: Boolean = false,
    val importing: Boolean = false,
    val requiresNewChat: Boolean = false,
    val error: String? = null,
    val attachments: List<String> = emptyList(),
    val agent: Boolean = false,
    val confirmation: AgentConfirmation? = null,
    val skills: List<SkillRow> = emptyList(),
    val packages: List<PackageRow> = emptyList(),
    val bundledPackages: String = "",
    val pendingSkill: SkillPreview? = null,
    val pendingPackages: List<String> = emptyList(),
    val toolsetBusy: Boolean = false,
    val accounts: List<AccountRow> = emptyList(),
    /** A quota or rate error is shown; the banner offers "Switch account", which only opens the list. */
    val offerSwitchAccount: Boolean = false,
    val balanceBusy: Boolean = false,
    val pane: WorkbenchPane? = null,
    val editorOutput: String? = null,
    val editorBusy: Boolean = false,
    /** The user's text size from Settings (0.85-1.3) on top of system font scaling. */
    val textScale: Float = 1f,
    val chatFiles: List<ChatFileRow>? = null,
    /** The API-key profile the API settings currently use (its chats are scoped to it); null for none. */
    val apiKeyProfile: String? = null,
    /** A session is being checked with DeepSeek (users/current); the app stays locked until it is confirmed. */
    val validating: Boolean = false,
    /** Contact details DeepSeek shows for the signed-in account (Settings > Account settings); memory only. */
    val profileEmail: String? = null,
    val profilePhone: String? = null,
    /** Settings > Personalization: finished thinking starts collapsed. */
    val collapseThinking: Boolean = true,
    /** Settings > Audio > Main language for dictation; empty follows the app language. */
    val audioLanguage: String = "",
)

/** Official palettes (shared with the API-key client, app.dsandroid.ui.ChatTheme) and type scale; see docs/reference/SCREEN_SPEC.md. */
@Composable
fun AccountTheme(appearance: Appearance, textScale: Float = 1f, content: @Composable () -> Unit) {
    val dark = appearance == Appearance.DARK || (appearance == Appearance.SYSTEM && isSystemInDarkTheme())
    CompositionLocalProvider(LocalOfficialColors provides if (dark) DarkOfficialColors else LightOfficialColors) {
        MaterialTheme(colorScheme = if (dark) DarkChatColors else LightChatColors, typography = OfficialTypography.scaled(textScale.coerceIn(0.85f, 1.3f)),
            content = content)
    }
}

/** Settings > Personalization: whether finished thinking starts collapsed. */
internal val LocalCollapseThinking = staticCompositionLocalOf { true }

/** Where Back goes from each page: settings sub-pages return to Settings, everything else to the chat. */
internal fun parentPage(page: AccountPage, signedIn: Boolean): AccountPage = when (page) {
    AccountPage.PROFILE, AccountPage.DATA_CONTROLS -> AccountPage.SETTINGS
    AccountPage.ACCOUNTS -> if (signedIn) AccountPage.SETTINGS else AccountPage.CHAT
    else -> AccountPage.CHAT
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeepSeekShell(
    state: AccountUiState,
    onLogin: () -> Unit = {}, onApiFallback: () -> Unit = {},
    onNavigate: (AccountPage) -> Unit = {}, onNew: () -> Unit = {}, onOpen: (String) -> Unit = {},
    onDraft: (String) -> Unit = {}, onSend: (Boolean, Boolean) -> Unit = { _, _ -> }, onStop: () -> Unit = {},
    onTheme: (Appearance) -> Unit = {}, onLogout: () -> Unit = {}, onError: (String) -> Unit = {},
    onAttachment: (String) -> Unit = {}, onRemoveAttachment: (Int) -> Unit = {},
    onFinishLogin: () -> Unit = {}, loginContent: @Composable () -> Unit = {},
    onBrowserSignIn: () -> Unit = {}, onPasteSession: () -> Unit = {}, onCopyDiagnostics: () -> Unit = {},
    onRename: (String, String) -> Unit = { _, _ -> }, onDelete: (String) -> Unit = {},
    onRegenerate: () -> Unit = {}, onEdit: (String, String) -> Unit = { _, _ -> },
    onApiKey: (String) -> Unit = {}, speechInput: AccountSpeechInput? = null, voiceTrigger: Int = 0, onVoiceHandled: () -> Unit = {},
    actions: AgentActions = AgentActions.None, accountActions: AccountActions = AccountActions.None,
    heldShare: Boolean = false, onUseApiKey: (String) -> Unit = {}, onOpenLink: (String) -> Unit = {}, onDismissNotice: () -> Unit = {},
    onPin: (String, Boolean) -> Unit = { _, _ -> }, onShareText: (String) -> Unit = {},
    onTextScale: (Float) -> Unit = {}, onCollapseThinking: (Boolean) -> Unit = {}, onAudioLanguage: (String) -> Unit = {},
    onAppLanguage: (String) -> Unit = {}, appLanguage: String = "", onDeleteAllChats: () -> Unit = {},
    onShareChat: () -> Unit = {}, onExportChatPdf: () -> Unit = {},
) {
    // The sign-in gate (navigation layer): until DeepSeek confirms a session only the welcome screen, the sign-in page and
    // the account list exist. No drawer, history, composer, files or tools are composed while signed out.
    if (!state.ready) { LoadingScreen(); return }
    if (!state.signedIn && state.page != AccountPage.LOGIN && state.page != AccountPage.ACCOUNTS) {
        WelcomeScreen(state, heldShare = heldShare, onBrowser = onBrowserSignIn, onGoogle = onBrowserSignIn, onPhoneEmail = onLogin,
            onPaste = onPasteSession, onUseApiKey = onUseApiKey, onAccounts = { onNavigate(AccountPage.ACCOUNTS) },
            onDismissNotice = onDismissNotice, onOpenLink = onOpenLink, onFinishLogout = onLogout)
        return
    }
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf("") }
    var attachmentTray by rememberSaveable { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<AccountHistory?>(null) }
    var renameTitle by remember { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<AccountHistory?>(null) }
    var editTarget by remember { mutableStateOf<AccountMessage?>(null) }
    var editText by remember { mutableStateOf("") }
    var sources by remember { mutableStateOf<List<WebCitation>?>(null) }
    var shareSheet by remember { mutableStateOf(false) }
    val settingsActions = object : SettingsActions {
        override fun theme(value: Appearance) = onTheme(value)
        override fun textScale(value: Float) = onTextScale(value)
        override fun collapseThinking(value: Boolean) = onCollapseThinking(value)
        override fun audioLanguage(tag: String) = onAudioLanguage(tag)
        override fun appLanguage(tag: String) = onAppLanguage(tag)
        override fun saveApiKey(value: String) = onApiKey(value)
        override fun apiFallback() = onApiFallback()
        override fun openLink(url: String) = onOpenLink(url)
        override fun deleteAllChats() = onDeleteAllChats()
        override fun logout() = onLogout()
    }
    BackHandler(drawer.isOpen) { scope.launch { drawer.close() } }
    BackHandler(!drawer.isOpen && state.page != AccountPage.CHAT) { onNavigate(parentPage(state.page, state.signedIn)) }
    CompositionLocalProvider(LocalCollapseThinking provides state.collapseThinking) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        ModalNavigationDrawer(drawerState = drawer, gesturesEnabled = state.page == AccountPage.CHAT, drawerContent = {
            HistoryDrawer(state, query, onQuery = { query = it.take(200) },
                onNew = { onNew(); scope.launch { drawer.close() } },
                onOpen = { id -> onOpen(id); scope.launch { drawer.close() } },
                onRename = { chat -> renameTarget = chat; renameTitle = chat.title }, onPin = { chat -> onPin(chat.id, !chat.pinned) },
                onDelete = { chat -> deleteTarget = chat },
                onFiles = { actions.openChatFiles(); scope.launch { drawer.close() } },
                onSkills = { onNavigate(AccountPage.SKILLS); scope.launch { drawer.close() } },
                onSettings = { onNavigate(AccountPage.SETTINGS); scope.launch { drawer.close() } })
        }) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
                if (state.page == AccountPage.CHAT) ChatTopBar(state, onMenu = { scope.launch { drawer.open() } }, onNew = onNew,
                    onShare = { shareSheet = true }.takeIf { state.messages.isNotEmpty() })
                else Box(Modifier.fillMaxWidth().heightIn(min = 56.dp).background(if (state.page in GROUPED_PAGES) LocalOfficialColors.current.groupedBackground else MaterialTheme.colorScheme.surface)) {
                    IconButton(onClick = { onNavigate(parentPage(state.page, state.signedIn)) }, modifier = Modifier.align(Alignment.CenterStart).padding(start = 4.dp)) {
                        AppIcon(Glyph.BACK, "Back", Modifier.size(22.dp))
                    }
                    Text(when (state.page) {
                        AccountPage.SETTINGS -> "Settings"; AccountPage.SKILLS -> "Skills"; AccountPage.ACCOUNTS -> "Accounts"
                        AccountPage.PROFILE -> "Account settings"; AccountPage.DATA_CONTROLS -> "Data controls"; else -> "Log in with DeepSeek"
                    }, Modifier.align(Alignment.Center).padding(horizontal = 72.dp), textAlign = TextAlign.Center, style = MaterialTheme.typography.titleMedium)
                }
                state.error?.let { error ->
                    Text(error, Modifier.fillMaxWidth().heightIn(max = 96.dp).background(MaterialTheme.colorScheme.errorContainer).verticalScroll(rememberScrollState()).padding(12.dp).testTag("account-error"),
                        color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodySmall)
                    // Quota and rate errors never change the account; this only opens the list so the user can choose.
                    if (state.offerSwitchAccount) Box(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.errorContainer)) {
                        TextButton(onClick = { onNavigate(AccountPage.ACCOUNTS) }, enabled = !state.busy, modifier = Modifier.padding(horizontal = 4.dp).testTag("switch-account")) {
                            Text("Switch account")
                        }
                    }
                }
                when (state.page) {
                    AccountPage.CHAT -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Column(Modifier.widthIn(max = 840.dp).fillMaxHeight()) {
                            if (state.messages.isEmpty()) EmptyChat(Modifier.weight(1f).fillMaxWidth())
                            else MessageList(state, Modifier.weight(1f).fillMaxWidth(), onRegenerate = onRegenerate,
                                onEdit = { message -> editTarget = message; editText = message.prompt ?: message.text },
                                onShareText = onShareText, onSources = { sources = it }, actions = actions)
                            if (state.requiresNewChat) TextButton(onClick = onNew, enabled = !state.busy, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Start a new chat to continue") }
                            state.attachments.forEachIndexed { index, name ->
                                Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                                    AppIcon(Glyph.DOCUMENT, modifier = Modifier.size(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(name, Modifier.weight(1f).padding(start = 8.dp), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                                    IconButton(onClick = { onRemoveAttachment(index) }, enabled = !state.busy) { AppIcon(Glyph.CLOSE, "Remove $name", Modifier.size(18.dp)) }
                                }
                            }
                            if (state.importing) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 24.dp))
                            ChatComposer(state, onDraft, onSend, onStop, { attachmentTray = true }, onError, speechInput, voiceTrigger, onVoiceHandled, actions::setAgent)
                        }
                    }
                    AccountPage.SETTINGS -> SettingsPage(state, settingsActions, appLanguage, onNavigate, Modifier.weight(1f))
                    AccountPage.PROFILE -> ProfilePage(state, settingsActions, Modifier.weight(1f))
                    AccountPage.DATA_CONTROLS -> DataControlsPage(state, settingsActions, Modifier.weight(1f))
                    AccountPage.SKILLS -> SkillsPage(state, actions, Modifier.weight(1f))
                    AccountPage.ACCOUNTS -> AccountsPage(state, accountActions, Modifier.weight(1f))
                    AccountPage.LOGIN -> {
                        Text(DeepSeekWebView.LOADING_STATUS, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).testTag("login-status"),
                            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        Text(DeepSeekWebView.SSO_NOTICE, Modifier.heightIn(max = 104.dp).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            TextButton(onClick = onLogin) { Text("Reload login") }
                            TextButton(onClick = onFinishLogin) { Text("Finish login") }
                        }
                        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            Button(onClick = onBrowserSignIn, modifier = Modifier.testTag("browser-sign-in"), shape = RoundedCornerShape(20.dp)) { Text("Continue with Google") }
                            TextButton(onClick = onPasteSession, modifier = Modifier.testTag("paste-session")) { Text("Paste session") }
                        }
                        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = onCopyDiagnostics, modifier = Modifier.testTag("copy-diagnostics")) { Text("Copy diagnostics") }
                        }
                        Box(Modifier.weight(1f).fillMaxWidth()) { loginContent() }
                    }
                }
            }
        }
    }
    }
    if (attachmentTray) AttachmentSheet(enabled = !state.busy && !state.importing, onDismiss = { attachmentTray = false },
        onPick = { label -> attachmentTray = false; onAttachment(label) })
    sources?.let { list -> SourcesSheet(list, onDismiss = { sources = null }, onOpen = onOpenLink) }
    if (shareSheet) ShareChatSheet(onDismiss = { shareSheet = false }, onShareText = { shareSheet = false; onShareChat() },
        onExportPdf = { shareSheet = false; onExportChatPdf() })
    state.chatFiles?.let { files ->
        ModalBottomSheet(onDismissRequest = actions::hideChatFiles) {
            Text("Files in this chat", Modifier.padding(horizontal = 24.dp), style = MaterialTheme.typography.titleMedium)
            Text("Text files open in the editor; HTML, SVG and Markdown open in the artifacts panel.", Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (files.isEmpty()) Text("No files yet. Files the agent makes and files you save from the editor appear here.",
                Modifier.padding(horizontal = 24.dp, vertical = 12.dp), style = MaterialTheme.typography.bodySmall)
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                items(files, key = { it.path }) { file ->
                    Row(Modifier.fillMaxWidth().clickable { actions.openChatFile(file) }.heightIn(min = 48.dp).padding(horizontal = 24.dp, vertical = 10.dp)
                        .testTag("chat-file-${file.name}"), verticalAlignment = Alignment.CenterVertically) {
                        Text(file.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(if (file.bytes < 1024) "${file.bytes} B" else "${(file.bytes + 1023) / 1024} KB", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Spacer(Modifier.navigationBarsPadding().height(16.dp))
        }
    }
    renameTarget?.let { chat ->
        AlertDialog(onDismissRequest = { renameTarget = null }, title = { Text("Rename Chat") },
            text = { OutlinedTextField(renameTitle, { renameTitle = it.take(100) }, singleLine = true, label = { Text("Title") }, modifier = Modifier.testTag("rename-title")) },
            confirmButton = { TextButton(onClick = { renameTarget = null; onRename(chat.id, renameTitle) }, enabled = renameTitle.isNotBlank()) { Text("Save") } },
            dismissButton = { TextButton(onClick = { renameTarget = null }) { Text("Cancel") } })
    }
    deleteTarget?.let { chat ->
        AlertDialog(onDismissRequest = { deleteTarget = null }, title = { Text("Delete chat?") },
            text = { Text("Once deleted, “${chat.title}” can't be recovered. It is deleted from DeepSeek and from this phone.") },
            confirmButton = { TextButton(onClick = { deleteTarget = null; onDelete(chat.id) }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Cancel") } })
    }
    editTarget?.let { message ->
        AlertDialog(onDismissRequest = { editTarget = null }, title = { Text("Edit message") },
            text = { Column {
                Text("Resending replaces this reply and later turns in this chat.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(editText, { editText = it.take(20_000) }, label = { Text("Message") }, modifier = Modifier.testTag("edit-message"))
            } },
            confirmButton = { TextButton(onClick = { editTarget = null; onEdit(message.id, editText) }, enabled = editText.isNotBlank()) { Text("Resend") } },
            dismissButton = { TextButton(onClick = { editTarget = null }) { Text("Cancel") } })
    }
    state.confirmation?.let { request ->
        AlertDialog(onDismissRequest = { actions.answerTool(false) }, title = { Text("Allow this action?") },
            text = { Text(request.summary, Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState()).testTag("tool-confirmation")) },
            confirmButton = { TextButton(onClick = { actions.answerTool(true) }) { Text("Allow once") } },
            dismissButton = { TextButton(onClick = { actions.answerTool(false) }) { Text("Deny") } })
    }
    state.pendingSkill?.let { skill ->
        AlertDialog(onDismissRequest = { actions.answerSkillInstall(false) }, title = { Text("Install skill ${skill.name} ${skill.version}?") },
            text = { Text(buildString {
                append(skill.description)
                if (skill.tools.isNotEmpty()) append("\n\nTools (run as Python on this phone): ${skill.tools.joinToString()}")
                if (skill.pip.isNotEmpty()) append("\n\nAlso downloads from PyPI: ${skill.pip.joinToString()}")
                append("\n\nOnly install skills you trust. You can disable or remove it later.")
            }, Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) },
            confirmButton = { TextButton(onClick = { actions.answerSkillInstall(true) }) { Text("Install") } },
            dismissButton = { TextButton(onClick = { actions.answerSkillInstall(false) }) { Text("Cancel") } })
    }
    if (state.pendingPackages.isNotEmpty()) AlertDialog(onDismissRequest = { actions.answerPackageInstall(false) }, title = { Text("Install Python packages?") },
        text = { Text("Download from PyPI into this app's private Python folder, with their dependencies:\n${state.pendingPackages.joinToString("\n")}") },
        confirmButton = { TextButton(onClick = { actions.answerPackageInstall(true) }) { Text("Install") } },
        dismissButton = { TextButton(onClick = { actions.answerPackageInstall(false) }) { Text("Cancel") } })
}

/** Installed skills (enable, remove, install from URL or file) and the runtime Python packages. */
@Composable
private fun SkillsPage(state: AccountUiState, actions: AgentActions, modifier: Modifier) {
    var url by rememberSaveable { mutableStateOf("") }
    var specs by rememberSaveable { mutableStateOf("") }
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Skills", style = MaterialTheme.typography.titleMedium)
        Text("Skills add instructions and tools that agent mode can use in any chat. Enabled skills are listed to DeepSeek by name and description; it loads one when a task needs it.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (state.toolsetBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
        state.skills.forEach { skill ->
            Row(Modifier.fillMaxWidth().testTag("skill-${skill.name}"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("${skill.name}  ${skill.version}", fontWeight = FontWeight.Medium)
                    Text(skill.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val tags = listOfNotNull("Built in".takeIf { skill.builtIn }, "${skill.tools} tool${if (skill.tools == 1) "" else "s"}".takeIf { skill.tools > 0 })
                    if (tags.isNotEmpty()) Text(tags.joinToString(" \u00B7 "), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (!skill.builtIn) TextButton(onClick = { actions.removeSkill(skill.name) }, enabled = !state.toolsetBusy,
                    modifier = Modifier.semantics { contentDescription = "Remove skill ${skill.name}" }) { Text("Remove") }
                Switch(checked = skill.enabled, onCheckedChange = { actions.setSkillEnabled(skill.name, it) }, enabled = !state.toolsetBusy,
                    modifier = Modifier.semantics { contentDescription = "Use skill ${skill.name}" })
            }
        }
        OutlinedTextField(url, { url = it.trim().take(2000) }, label = { Text("Skill .zip link (https)") }, singleLine = true,
            enabled = !state.toolsetBusy, modifier = Modifier.fillMaxWidth().testTag("skill-url"))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { actions.installSkillFromUrl(url) }, enabled = url.isNotBlank() && !state.toolsetBusy) { Text("Install from link") }
            TextButton(onClick = { actions.installSkillFromFile() }, enabled = !state.toolsetBusy) { Text("Install from file") }
        }
        Text("A skill is a folder zipped with manifest.json (name, description, version, tools, pip), SKILL.md and an optional main.py whose functions become tools.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        HorizontalDivider()
        Text("Python toolsets", style = MaterialTheme.typography.titleMedium)
        Text(state.bundledPackages, style = MaterialTheme.typography.bodySmall)
        if (state.packages.isEmpty()) Text("No extra packages installed.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        state.packages.forEach { item ->
            Row(Modifier.fillMaxWidth().testTag("package-${item.name}"), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("${item.name} ${item.version}")
                    Text("${(item.bytes + 1023) / 1024} KB", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = { actions.removePackage(item.name) }, enabled = !state.toolsetBusy,
                    modifier = Modifier.semantics { contentDescription = "Remove package ${item.name}" }) { Text("Remove") }
            }
        }
        OutlinedTextField(specs, { specs = it.take(500) }, label = { Text("Pure-Python packages, e.g. tabulate") }, singleLine = true,
            enabled = !state.toolsetBusy, modifier = Modifier.fillMaxWidth().testTag("package-specs"))
        TextButton(onClick = { actions.installPackages(specs); specs = "" }, enabled = specs.isNotBlank() && !state.toolsetBusy) { Text("Install packages") }
        Text("Packages download from PyPI after you confirm. Only pure-Python wheels install on the phone; packages with native code cannot.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
