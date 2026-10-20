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
import app.dsandroid.core.editor.EditorLanguage
import app.dsandroid.core.tools.Artifact
import app.dsandroid.data.Appearance
import app.dsandroid.ui.AppIcon
import app.dsandroid.ui.Glyph
import app.dsandroid.ui.MarkdownText
import app.dsandroid.workbench.WorkbenchPane
import java.io.File
import kotlinx.coroutines.launch

enum class AccountPage { CHAT, SETTINGS, LOGIN, SKILLS, ACCOUNTS }
data class AccountMessage(
    val id: String, val user: Boolean, val text: String, val thinking: String = "", val prompt: String? = null,
    val steps: List<TurnStep> = emptyList(), val artifacts: List<Artifact> = emptyList(), val codeRuns: List<TurnStep.ToolCall> = emptyList(),
    val revisionOf: String? = null,
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
data class AccountHistory(val id: String, val title: String)
data class AccountUiState(
    val ready: Boolean = false,
    val signedIn: Boolean = false,
    val hasApiKey: Boolean = false,
    val logoutPending: Boolean = false,
    val page: AccountPage = AccountPage.CHAT,
    val appearance: Appearance = Appearance.SYSTEM,
    val title: String = "New Chat",
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
)

/** Light measurements follow ui-spec.md; the dark palette is an original interpretation. */
@Composable
fun AccountTheme(appearance: Appearance, content: @Composable () -> Unit) {
    val dark = appearance == Appearance.DARK || (appearance == Appearance.SYSTEM && isSystemInDarkTheme())
    val colors = if (dark) darkColorScheme(
        primary = Color(0xFFACBEFF), onPrimary = Color(0xFF142550), primaryContainer = Color(0xFF263653), onPrimaryContainer = Color(0xFFD6E2FF),
        surface = Color(0xFF151619), background = Color(0xFF151619), onSurface = Color(0xFFEDEEF2), onBackground = Color(0xFFEDEEF2),
        surfaceVariant = Color(0xFF23252B), onSurfaceVariant = Color(0xFFB9BBC3), outlineVariant = Color(0xFF3A3D46),
    ) else lightColorScheme(
        primary = Color(0xFF426EFE), onPrimary = Color.White, primaryContainer = Color(0xFFF3F7FF), onPrimaryContainer = Color(0xFF3154C7),
        surface = Color.White, background = Color.White, onSurface = Color(0xFF1A1A1A), onBackground = Color(0xFF1A1A1A),
        surfaceVariant = Color(0xFFF5F6F8), onSurfaceVariant = Color(0xFF6B6F76), outlineVariant = Color(0xFFE9EAED),
    )
    val body = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 14.sp, lineHeight = 21.sp)
    MaterialTheme(colorScheme = colors, typography = Typography(
        bodyLarge = body, bodyMedium = body, bodySmall = body.copy(fontSize = 12.sp, lineHeight = 18.sp),
        titleMedium = body.copy(fontSize = 16.sp, fontWeight = FontWeight.Medium),
        titleLarge = body.copy(fontSize = 18.sp, fontWeight = FontWeight.Bold),
        headlineSmall = body.copy(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.Bold),
    ), content = content)
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
) {
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf("") }
    var attachmentTray by rememberSaveable { mutableStateOf(false) }
    var logoutDialog by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<AccountHistory?>(null) }
    var renameTitle by remember { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<AccountHistory?>(null) }
    var editTarget by remember { mutableStateOf<AccountMessage?>(null) }
    var editText by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    BackHandler(drawer.isOpen) { scope.launch { drawer.close() } }
    BackHandler(!drawer.isOpen && state.page != AccountPage.CHAT) { onNavigate(AccountPage.CHAT) }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        ModalNavigationDrawer(drawerState = drawer, gesturesEnabled = state.page == AccountPage.CHAT, drawerContent = {
            ModalDrawerSheet(Modifier.fillMaxWidth(.86f).widthIn(max = 360.dp), drawerContainerColor = MaterialTheme.colorScheme.surfaceVariant) {
                Text("DeepSeek", Modifier.padding(20.dp), style = MaterialTheme.typography.titleLarge)
                OutlinedTextField(query, { query = it }, label = { Text("Search chats") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("history-search"), shape = RoundedCornerShape(18.dp))
                NavigationDrawerItem(label = { Text("New Chat") }, selected = false, icon = { AppIcon(Glyph.PLUS) },
                    onClick = { onNew(); scope.launch { drawer.close() } }, modifier = Modifier.padding(12.dp))
                Text("Recent chats", Modifier.padding(horizontal = 20.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                LazyColumn(Modifier.weight(1f)) {
                    items(state.history.filter { it.title.contains(query, true) }, key = { it.id }) { chat ->
                        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(chat.title, Modifier.weight(1f).testTag("history-open-${chat.id}").clickable(enabled = !state.busy) {
                                onOpen(chat.id); scope.launch { drawer.close() }
                            }.padding(horizontal = 12.dp, vertical = 16.dp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                            TextButton(onClick = { renameTarget = chat; renameTitle = chat.title }, enabled = !state.busy,
                                modifier = Modifier.semantics { contentDescription = "Rename ${chat.title}" }) { Text("Rename", style = MaterialTheme.typography.bodySmall) }
                            IconButton(onClick = { deleteTarget = chat }, enabled = !state.busy) { AppIcon(Glyph.CLOSE, "Delete ${chat.title}") }
                        }
                    }
                }
                HorizontalDivider()
                NavigationDrawerItem(label = { Text("Files in this chat") }, selected = false, icon = { AppIcon(Glyph.DOCUMENT) },
                    onClick = { actions.openChatFiles(); scope.launch { drawer.close() } }, modifier = Modifier.padding(horizontal = 12.dp).testTag("chat-files"))
                NavigationDrawerItem(label = { Text("Skills") }, selected = false, icon = { AppIcon(Glyph.TOOL) },
                    onClick = { onNavigate(AccountPage.SKILLS); scope.launch { drawer.close() } }, modifier = Modifier.padding(horizontal = 12.dp))
                NavigationDrawerItem(label = { Text("Settings") }, selected = false,
                    onClick = { onNavigate(AccountPage.SETTINGS); scope.launch { drawer.close() } }, modifier = Modifier.padding(12.dp))
            }
        }) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
                Box(Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                    if (state.page == AccountPage.CHAT) {
                        IconButton(onClick = { scope.launch { drawer.open() } }, modifier = Modifier.align(Alignment.CenterStart).size(48.dp)) { AppIcon(Glyph.MENU, "Open navigation") }
                        Column(Modifier.align(Alignment.Center).padding(horizontal = 64.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(state.title.ifBlank { "New Chat" }, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            // With several accounts, the one in use is always visible; it changes only from Settings > Accounts.
                            if (state.accounts.size > 1) state.accounts.firstOrNull { it.active }?.let { active ->
                                Text(active.label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                                    overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("active-account"))
                            }
                        }
                        IconButton(onClick = onNew, enabled = !state.busy, modifier = Modifier.align(Alignment.CenterEnd).size(48.dp)) {
                            OutlinedCircle { AppIcon(Glyph.PLUS, "New Chat", Modifier.size(14.dp)) }
                        }
                    } else {
                        TextButton(onClick = { onNavigate(AccountPage.CHAT) }, modifier = Modifier.align(Alignment.CenterStart)) { Text("Back") }
                        Text(when (state.page) { AccountPage.SETTINGS -> "Settings"; AccountPage.SKILLS -> "Skills"; AccountPage.ACCOUNTS -> "Accounts"; else -> "Log in with DeepSeek" }, Modifier.align(Alignment.Center).padding(horizontal = 72.dp),
                            textAlign = TextAlign.Center, style = MaterialTheme.typography.titleMedium)
                    }
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
                            if (state.messages.isEmpty()) {
                                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                                    OriginalMark()
                                    Spacer(Modifier.height(16.dp))
                                    Text(if (state.ready && !state.signedIn) "Sign in to start chatting" else "How can I help you?", style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                                    if (state.ready && !state.signedIn) {
                                        Spacer(Modifier.height(20.dp))
                                        SignInCard(onBrowser = onBrowserSignIn, onPaste = onPasteSession, onInApp = onLogin)
                                    } else if (!state.ready) Text("Loading…", Modifier.padding(12.dp))
                                }
                            } else {
                                val list = rememberLazyListState()
                                LaunchedEffect(state.messages.lastOrNull()?.text, state.messages.lastOrNull()?.thinking) {
                                    if (state.busy && state.messages.isNotEmpty()) list.scrollToItem(state.messages.lastIndex)
                                }
                                LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("message-list"), state = list, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                                    items(state.messages, key = { it.id }) { message ->
                                        Message(message, state.busy && message.id == state.messages.lastOrNull()?.id,
                                            enabled = !state.busy, regenerate = !message.user && message.id == state.messages.lastOrNull()?.id,
                                            onRegenerate = onRegenerate, onEdit = { editTarget = message; editText = message.prompt ?: message.text }, actions = actions)
                                    }
                                }
                            }
                            if (state.ready && !state.signedIn && state.messages.isNotEmpty()) SignInCard(onBrowser = onBrowserSignIn, onPaste = onPasteSession, onInApp = onLogin,
                                modifier = Modifier.align(Alignment.CenterHorizontally).padding(vertical = 8.dp))
                            if (state.requiresNewChat) TextButton(onClick = onNew, enabled = !state.busy, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Start a new chat to continue") }
                            state.attachments.forEachIndexed { index, name ->
                                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                                    IconButton(onClick = { onRemoveAttachment(index) }, enabled = !state.busy) { AppIcon(Glyph.CLOSE, "Remove $name") }
                                }
                            }
                            if (state.importing) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 20.dp))
                            Composer(state, onDraft, onSend, onStop, { attachmentTray = true }, onError, speechInput, voiceTrigger, onVoiceHandled, actions::setAgent)
                        }
                    }
                    AccountPage.SETTINGS -> Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                        Text("Account", style = MaterialTheme.typography.titleMedium)
                        if (state.signedIn || state.logoutPending) {
                            Text(if (state.logoutPending) "Session cleanup needs attention" else "DeepSeek account connected")
                            OutlinedButton(onClick = { logoutDialog = true }, enabled = !state.busy) { Text(if (state.logoutPending) "Retry logout" else "Log out") }
                        } else Button(onClick = onLogin) { Text("Log in with DeepSeek") }
                        HorizontalDivider()
                        Text("Accounts", style = MaterialTheme.typography.titleMedium)
                        val inUse = state.accounts.firstOrNull { it.active }
                        Text(if (inUse == null) "No account in use. Sign in, or choose one of your accounts." else "In use: ${inUse.label} (${inUse.type.label})",
                            style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { onNavigate(AccountPage.ACCOUNTS) }, enabled = !state.busy, modifier = Modifier.testTag("manage-accounts")) {
                            Text("Manage accounts (${state.accounts.size})")
                        }
                        HorizontalDivider()
                        Text("Theme", style = MaterialTheme.typography.titleMedium)
                        Appearance.entries.forEach { appearance ->
                            Row(Modifier.fillMaxWidth().clickable { onTheme(appearance) }.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selected = state.appearance == appearance, onClick = { onTheme(appearance) })
                                Text(when (appearance) { Appearance.SYSTEM -> "System"; Appearance.LIGHT -> "Light"; Appearance.DARK -> "Dark" })
                            }
                        }
                        HorizontalDivider()
                        Text("API-key mode", style = MaterialTheme.typography.titleMedium)
                        OutlinedTextField(apiKey, { apiKey = it }, label = { Text("DeepSeek API key") }, singleLine = true,
                            visualTransformation = PasswordVisualTransformation(), enabled = !state.busy,
                            modifier = Modifier.fillMaxWidth().testTag("fallback-key"))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { onApiKey(apiKey); apiKey = "" }, enabled = !state.busy && apiKey.isNotBlank()) { Text("Save API key") }
                            if (state.hasApiKey) TextButton(onClick = { apiKey = ""; onApiKey("") }, enabled = !state.busy) { Text("Clear API key") }
                        }
                        if (state.hasApiKey) Text("API key saved securely", style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = onApiFallback, enabled = !state.busy) { Text("API-key fallback") }
                        Text("Uses the separate API chat client and may use paid API credits. You can return to your DeepSeek account at any time.", style = MaterialTheme.typography.bodySmall)
                        HorizontalDivider()
                        Text("About", style = MaterialTheme.typography.titleMedium)
                        Text("DeepSeek Android \u00B7 ${app.dsandroid.BuildConfig.VERSION_NAME}\nUnofficial client. Not affiliated with or endorsed by DeepSeek.")
                        Text("Light layout follows the measured reference. Dark colors and the abstract mark are original interpretations. Web login and chat use an unofficial protocol that may change.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        HorizontalDivider()
                        Text("Agent", style = MaterialTheme.typography.titleMedium)
                        Text("Agent mode lets DeepSeek use tools on this phone: Python, files, PDFs and documents, skills and web pages. Downloads and network access ask first.", style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { onNavigate(AccountPage.SKILLS) }, enabled = !state.busy) { Text("Skills and Python toolsets") }
                    }
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
    if (attachmentTray) ModalBottomSheet(onDismissRequest = { attachmentTray = false }) {
        Text("Add to your message", Modifier.padding(horizontal = 24.dp), style = MaterialTheme.typography.titleMedium)
        Text("Files and images are uploaded to DeepSeek after you confirm sending.", Modifier.padding(horizontal = 24.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall)
        Row(Modifier.fillMaxWidth().padding(20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf("Camera" to Glyph.CAMERA, "Photo" to Glyph.PHOTO, "Document" to Glyph.DOCUMENT).forEach { (label, icon) ->
                Surface(Modifier.weight(1f).clickable(enabled = !state.busy && !state.importing) { attachmentTray = false; onAttachment(label) },
                    color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(14.dp)) {
                    Column(Modifier.padding(12.dp).heightIn(min = 64.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) { AppIcon(icon); Text(label, textAlign = TextAlign.Center) }
                }
            }
        }
    }
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
        AlertDialog(onDismissRequest = { renameTarget = null }, title = { Text("Rename chat") },
            text = { OutlinedTextField(renameTitle, { renameTitle = it.take(100) }, singleLine = true, label = { Text("Title") }, modifier = Modifier.testTag("rename-title")) },
            confirmButton = { TextButton(onClick = { renameTarget = null; onRename(chat.id, renameTitle) }, enabled = renameTitle.isNotBlank()) { Text("Save") } },
            dismissButton = { TextButton(onClick = { renameTarget = null }) { Text("Cancel") } })
    }
    deleteTarget?.let { chat ->
        AlertDialog(onDismissRequest = { deleteTarget = null }, title = { Text("Delete chat?") },
            text = { Text("Delete “${chat.title}” from DeepSeek and this device? This cannot be undone.") },
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
    if (logoutDialog) AlertDialog(onDismissRequest = { logoutDialog = false }, title = { Text("Log out of DeepSeek?") },
        text = { Text("This clears this app's DeepSeek session and local account chat history. Other sites' cookies are kept.") },
        confirmButton = { TextButton(onClick = { logoutDialog = false; onLogout() }) { Text("Log out") } },
        dismissButton = { TextButton(onClick = { logoutDialog = false }) { Text("Cancel") } })
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

@Composable
private fun OriginalMark() {
    val blue = MaterialTheme.colorScheme.primary
    Canvas(Modifier.size(width = 64.dp, height = 44.dp).semantics { contentDescription = "Original abstract mark" }) {
        // Own simple whale silhouette drawn in code; no official logo geometry or extracted assets.
        val w = size.width; val h = size.height
        val body = androidx.compose.ui.graphics.Path().apply {
            moveTo(w * .06f, h * .52f)
            cubicTo(w * .08f, h * .18f, w * .52f, h * .10f, w * .70f, h * .40f)
            lineTo(w * .86f, h * .12f); lineTo(w * .97f, h * .20f); lineTo(w * .84f, h * .48f)
            cubicTo(w * .80f, h * .86f, w * .40f, h * 1.0f, w * .20f, h * .86f)
            cubicTo(w * .10f, h * .78f, w * .05f, h * .66f, w * .06f, h * .52f); close()
        }
        drawPath(body, blue)
        drawCircle(androidx.compose.ui.graphics.Color.White, radius = w * .035f, center = Offset(w * .26f, h * .44f))
        drawLine(androidx.compose.ui.graphics.Color.White, Offset(w * .16f, h * .66f), Offset(w * .50f, h * .70f), w * .03f, StrokeCap.Round)
    }
}

@Composable
private fun OutlinedCircle(content: @Composable () -> Unit) {
    Box(Modifier.size(26.dp).border(1.5.dp, MaterialTheme.colorScheme.onSurface, CircleShape), contentAlignment = Alignment.Center) { content() }
}

@Suppress("DEPRECATION")
@Composable
private fun Message(message: AccountMessage, working: Boolean, enabled: Boolean, regenerate: Boolean, onRegenerate: () -> Unit, onEdit: () -> Unit,
    actions: AgentActions = AgentActions.None) {
    val clipboard = LocalClipboardManager.current
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (message.user) Alignment.End else Alignment.Start) {
        if (message.user) BoxWithConstraints(Modifier.fillMaxWidth()) {
            Surface(Modifier.align(Alignment.CenterEnd).widthIn(max = maxWidth * .8f), shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                Text(message.text, Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onSurface)
            }
        } else Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val onRun: (String) -> Unit = { code -> actions.runCode(message.id, code) }
            val runCode = onRun.takeIf { enabled }
            // Finished code blocks open in the artifacts panel (HTML, SVG, Markdown) or in the editor (other languages).
            val onPreview: (String, String) -> Unit = { language, code -> actions.previewCode(message.id, message.revisionOf, language, code) }
            val onEdit: (String, String) -> Unit = { language, code -> actions.editCode(language, code) }
            val preview = onPreview.takeIf { !working }
            val edit = onEdit.takeIf { !working }
            if (message.steps.isNotEmpty()) AgentTimeline(message.id, message.steps, working, runCode, preview, edit)
            else {
                var expanded by rememberSaveable(message.id) { mutableStateOf(false) }
                if (message.thinking.isNotBlank()) {
                    Row(Modifier.testTag("thinking-${message.id}").clickable { expanded = !expanded }.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(if (working && message.text.isEmpty()) "Thinking…" else "Thinking", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                        AppIcon(Glyph.CHEVRON, if (expanded) "Collapse thinking" else "Expand thinking", Modifier.rotate(if (expanded) 180f else 0f))
                    }
                    if (expanded) Text(message.thinking, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
                if (message.text.isNotBlank()) MarkdownText(message.text, onRunCode = runCode, onPreviewCode = preview, onEditCode = edit)
                else if (working) Text("Connecting…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            message.codeRuns.forEach { ToolStepRow(it) }
            message.artifacts.forEach { artifact ->
                ArtifactCard(artifact, actions.artifactFile(artifact), onOpen = { actions.openArtifact(artifact) },
                    onSave = { actions.saveArtifact(artifact) }, onShare = { actions.shareArtifact(artifact) },
                    onEdit = { actions.editArtifact(artifact) }.takeIf { EditorLanguage.forFileName(artifact.fileName) != null })
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { clipboard.setText(AnnotatedString(message.text)) }, enabled = message.text.isNotEmpty()) {
                AppIcon(Glyph.DOCUMENT, if (message.user) "Copy user message" else "Copy assistant message")
            }
            if (message.user) TextButton(onClick = onEdit, enabled = enabled, modifier = Modifier.testTag("edit-${message.id}").semantics { contentDescription = "Edit message" }) { Text("Edit") }
            if (regenerate) TextButton(onClick = onRegenerate, enabled = enabled, modifier = Modifier.semantics { contentDescription = "Regenerate response" }) { Text("Regenerate") }
            if (!message.user && message.text.isNotBlank()) TextButton(onClick = { actions.exportPdf(message.id) }, enabled = enabled,
                modifier = Modifier.testTag("export-${message.id}").semantics { contentDescription = "Export as PDF" }) { Text("Export PDF") }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Composer(state: AccountUiState, draft: (String) -> Unit, send: (Boolean, Boolean) -> Unit, stop: () -> Unit, attach: () -> Unit, error: (String) -> Unit, speechInput: AccountSpeechInput?, voiceTrigger: Int, onVoiceHandled: () -> Unit,
    onAgent: (Boolean) -> Unit = {}) {
    var think by rememberSaveable { mutableStateOf(false) }
    var search by rememberSaveable { mutableStateOf(false) }
    // Signed out (no DeepSeek session; an API-key profile uses its own screen): no typing, no send, no chips or attachments; a tap on the field opens sign-in.
    val locked = state.ready && !state.signedIn
    val pillColors = FilterChipDefaults.filterChipColors(
        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
        selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
    )
    Surface(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp), shape = RoundedCornerShape(22.dp),
        border = androidx.compose.foundation.BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .6f)),
        color = MaterialTheme.colorScheme.surface, shadowElevation = 6.dp) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
            BasicTextField(state.draft, draft, Modifier.fillMaxWidth().heightIn(min = 36.dp).padding(top = 8.dp).testTag("account-composer")
                .semantics { contentDescription = if (locked) "Log in to chat" else "Type a message" }
                .then(if (locked) Modifier.clickable(onClickLabel = "Log in") { send(false, false) } else Modifier),
                maxLines = 6, enabled = !state.busy && state.ready && !locked,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface), cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                decorationBox = { inner -> Box { if (state.draft.isEmpty()) Text(if (locked) "Log in to chat" else "Type a message or hold to speak", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp); inner() } })
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalArrangement = Arrangement.Center) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(selected = think, onClick = { think = !think }, label = { Text("Think", fontSize = 12.sp) },
                        leadingIcon = { AppIcon(Glyph.THINK, modifier = Modifier.size(16.dp)) }, enabled = !state.busy && !locked, shape = RoundedCornerShape(18.dp), colors = pillColors,
                        border = FilterChipDefaults.filterChipBorder(enabled = !state.busy && !locked, selected = false, borderColor = MaterialTheme.colorScheme.outlineVariant))
                    FilterChip(selected = search, onClick = { search = !search }, label = { Text("Search", fontSize = 12.sp) },
                        leadingIcon = { AppIcon(Glyph.SEARCH, modifier = Modifier.size(16.dp)) }, enabled = !state.busy && !locked, shape = RoundedCornerShape(18.dp), colors = pillColors,
                        border = FilterChipDefaults.filterChipBorder(enabled = !state.busy && !locked, selected = false, borderColor = MaterialTheme.colorScheme.outlineVariant))
                    FilterChip(selected = state.agent, onClick = { onAgent(!state.agent) }, label = { Text("Agent", fontSize = 12.sp) },
                        leadingIcon = { AppIcon(Glyph.TOOL, modifier = Modifier.size(16.dp)) }, enabled = !state.busy && !locked, shape = RoundedCornerShape(18.dp), colors = pillColors,
                        border = FilterChipDefaults.filterChipBorder(enabled = !state.busy && !locked, selected = false, borderColor = MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier.testTag("agent-toggle").semantics { contentDescription = if (state.agent) "Agent mode on" else "Agent mode off" })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = attach, enabled = !state.busy && !state.importing && !locked) { OutlinedCircle { AppIcon(Glyph.PLUS, "Add attachment", Modifier.size(14.dp)) } }
                    key(state.conversationId) {
                        AccountDictation(!state.busy && state.ready && !locked, { draft(listOf(state.draft, it).filter(String::isNotBlank).joinToString(" ")) }, error,
                            speechInput, voiceTrigger, onVoiceHandled)
                    }
                    IconButton(onClick = { if (state.busy) stop() else send(think, search) }, enabled = state.busy ||
                        (!locked && state.ready && !state.importing && !state.requiresNewChat && (state.draft.isNotBlank() || state.attachments.isNotEmpty()))) {
                        val canSend = state.busy || state.draft.isNotBlank() || state.attachments.isNotEmpty()
                        if (canSend) Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary) {
                            AppIcon(if (state.busy) Glyph.STOP else Glyph.SEND, if (state.busy) "Stop response" else "Send message", Modifier.padding(6.dp))
                        } else OutlinedCircle { AppIcon(Glyph.SEND, "Send message", Modifier.size(14.dp)) }
                    }
                }
            }
        }
    }
}

/**
 * Signed out: every way to sign in, on the first screen. The browser route comes first because it works on every
 * phone, including ones where the in-app sign-in page stays blank; the in-app page is the secondary option.
 */
@Composable
private fun SignInCard(onBrowser: () -> Unit, onPaste: () -> Unit, onInApp: () -> Unit, modifier: Modifier = Modifier) {
    Surface(modifier.padding(horizontal = 20.dp).widthIn(max = 420.dp).fillMaxWidth().testTag("signin-card"), shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("You're signed out. Chat is off until you sign in with your DeepSeek account.", textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = onBrowser, modifier = Modifier.fillMaxWidth().testTag("card-google"), shape = RoundedCornerShape(24.dp)) { Text("Continue with Google") }
            OutlinedButton(onClick = onBrowser, modifier = Modifier.fillMaxWidth().testTag("card-browser"), shape = RoundedCornerShape(24.dp)) { Text("Sign in with browser") }
            Text("Both open your browser once, then bring the session back to this app.", textAlign = TextAlign.Center,
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = onInApp, modifier = Modifier.testTag("card-in-app")) { Text("Log in with DeepSeek") }
                TextButton(onClick = onPaste, modifier = Modifier.testTag("card-paste")) { Text("Paste session") }
            }
        }
    }
}
