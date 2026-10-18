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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.dsandroid.data.Appearance
import app.dsandroid.ui.AppIcon
import app.dsandroid.ui.Glyph
import app.dsandroid.ui.MarkdownText
import app.dsandroid.ui.VoiceDictation
import kotlinx.coroutines.launch

enum class AccountPage { CHAT, SETTINGS, LOGIN }
data class AccountMessage(val id: String, val user: Boolean, val text: String, val thinking: String = "")
data class AccountHistory(val id: String, val title: String)
data class AccountUiState(
    val ready: Boolean = false,
    val signedIn: Boolean = false,
    val logoutPending: Boolean = false,
    val page: AccountPage = AccountPage.CHAT,
    val appearance: Appearance = Appearance.SYSTEM,
    val title: String = "New Chat",
    val messages: List<AccountMessage> = emptyList(),
    val history: List<AccountHistory> = emptyList(),
    val draft: String = "",
    val busy: Boolean = false,
    val importing: Boolean = false,
    val requiresNewChat: Boolean = false,
    val error: String? = null,
    val attachments: List<String> = emptyList(),
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
) {
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf("") }
    var attachmentTray by rememberSaveable { mutableStateOf(false) }
    var logoutDialog by remember { mutableStateOf(false) }
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
                        Text(chat.title, Modifier.fillMaxWidth().clickable(enabled = !state.busy) {
                            onOpen(chat.id); scope.launch { drawer.close() }
                        }.padding(horizontal = 20.dp, vertical = 16.dp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
                HorizontalDivider()
                NavigationDrawerItem(label = { Text("Settings") }, selected = false,
                    onClick = { onNavigate(AccountPage.SETTINGS); scope.launch { drawer.close() } }, modifier = Modifier.padding(12.dp))
            }
        }) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
                Box(Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                    if (state.page == AccountPage.CHAT) {
                        IconButton(onClick = { scope.launch { drawer.open() } }, modifier = Modifier.align(Alignment.CenterStart).size(48.dp)) { AppIcon(Glyph.MENU, "Open navigation") }
                        Text(state.title.ifBlank { "New Chat" }, Modifier.align(Alignment.Center).padding(horizontal = 64.dp),
                            fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        IconButton(onClick = onNew, enabled = !state.busy, modifier = Modifier.align(Alignment.CenterEnd).size(48.dp)) {
                            OutlinedCircle { AppIcon(Glyph.PLUS, "New Chat", Modifier.size(14.dp)) }
                        }
                    } else {
                        TextButton(onClick = { onNavigate(AccountPage.CHAT) }, modifier = Modifier.align(Alignment.CenterStart)) { Text("Back") }
                        Text(if (state.page == AccountPage.SETTINGS) "Settings" else "Log in with DeepSeek", Modifier.align(Alignment.Center).padding(horizontal = 72.dp),
                            textAlign = TextAlign.Center, style = MaterialTheme.typography.titleMedium)
                    }
                }
                state.error?.let { error ->
                    Text(error, Modifier.fillMaxWidth().heightIn(max = 96.dp).background(MaterialTheme.colorScheme.errorContainer).verticalScroll(rememberScrollState()).padding(12.dp).testTag("account-error"),
                        color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodySmall)
                }
                when (state.page) {
                    AccountPage.CHAT -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Column(Modifier.widthIn(max = 840.dp).fillMaxHeight()) {
                            if (state.messages.isEmpty()) {
                                Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                                    OriginalMark()
                                    Spacer(Modifier.height(16.dp))
                                    Text("How can I help you?", style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                                    if (state.ready && !state.signedIn) {
                                        Spacer(Modifier.height(20.dp))
                                        Button(onClick = onLogin, shape = RoundedCornerShape(24.dp)) { Text("Log in with DeepSeek") }
                                        Text("Use your DeepSeek account", Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                                    } else if (!state.ready) Text("Loading…", Modifier.padding(12.dp))
                                }
                            } else {
                                val list = rememberLazyListState()
                                LaunchedEffect(state.messages.lastOrNull()?.text, state.messages.lastOrNull()?.thinking) {
                                    if (state.busy && state.messages.isNotEmpty()) list.scrollToItem(state.messages.lastIndex)
                                }
                                LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                                    items(state.messages, key = { it.id }) { message -> Message(message, state.busy && message.id == state.messages.lastOrNull()?.id) }
                                }
                            }
                            if (!state.signedIn && state.messages.isNotEmpty()) TextButton(onClick = onLogin, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Log in with DeepSeek") }
                            if (state.requiresNewChat) TextButton(onClick = onNew, enabled = !state.busy, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Start a new chat to continue") }
                            state.attachments.forEachIndexed { index, name ->
                                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text("$name · extracted text", Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                                    IconButton(onClick = { onRemoveAttachment(index) }, enabled = !state.busy) { AppIcon(Glyph.CLOSE, "Remove $name") }
                                }
                            }
                            if (state.importing) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 20.dp))
                            Composer(state, onDraft, onSend, onStop, { attachmentTray = true }, onError)
                        }
                    }
                    AccountPage.SETTINGS -> Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                        Text("Account", style = MaterialTheme.typography.titleMedium)
                        if (state.signedIn || state.logoutPending) {
                            Text(if (state.logoutPending) "Session cleanup needs attention" else "DeepSeek account connected")
                            OutlinedButton(onClick = { logoutDialog = true }, enabled = !state.busy) { Text(if (state.logoutPending) "Retry logout" else "Log out") }
                        } else Button(onClick = onLogin) { Text("Log in with DeepSeek") }
                        HorizontalDivider()
                        Text("Theme", style = MaterialTheme.typography.titleMedium)
                        Appearance.entries.forEach { appearance ->
                            Row(Modifier.fillMaxWidth().clickable { onTheme(appearance) }.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selected = state.appearance == appearance, onClick = { onTheme(appearance) })
                                Text(when (appearance) { Appearance.SYSTEM -> "System"; Appearance.LIGHT -> "Light"; Appearance.DARK -> "Dark" })
                            }
                        }
                        HorizontalDivider()
                        TextButton(onClick = onApiFallback, enabled = !state.busy) { Text("API-key fallback") }
                        Text("Configure the separate API client in its Settings. Sending there may use paid API credits; it is never selected automatically.", style = MaterialTheme.typography.bodySmall)
                        HorizontalDivider()
                        Text("About", style = MaterialTheme.typography.titleMedium)
                        Text("DeepSeek Android · 0.5.0\nUnofficial client. Not affiliated with or endorsed by DeepSeek.")
                        Text("Light layout follows the measured reference. Dark colors and the abstract mark are original interpretations. Web login and chat use an unofficial protocol that may change.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    AccountPage.LOGIN -> {
                        Text(DeepSeekWebView.SSO_NOTICE, Modifier.heightIn(max = 104.dp).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            TextButton(onClick = onLogin) { Text("Reload login") }
                            TextButton(onClick = onFinishLogin) { Text("Finish login") }
                        }
                        Box(Modifier.weight(1f).fillMaxWidth()) { loginContent() }
                    }
                }
            }
        }
    }
    if (attachmentTray) ModalBottomSheet(onDismissRequest = { attachmentTray = false }) {
        Text("Add to your message", Modifier.padding(horizontal = 24.dp), style = MaterialTheme.typography.titleMedium)
        Text("Extracted text only. Photos use on-device text recognition; original files are not uploaded.", Modifier.padding(horizontal = 24.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall)
        Row(Modifier.fillMaxWidth().padding(20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf("Camera" to Glyph.CAMERA, "Photo" to Glyph.PHOTO, "Document" to Glyph.DOCUMENT).forEach { (label, icon) ->
                Surface(Modifier.weight(1f).clickable(enabled = !state.busy && !state.importing) { attachmentTray = false; onAttachment(label) },
                    color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(14.dp)) {
                    Column(Modifier.padding(12.dp).heightIn(min = 64.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) { AppIcon(icon); Text(label, textAlign = TextAlign.Center) }
                }
            }
        }
    }
    if (logoutDialog) AlertDialog(onDismissRequest = { logoutDialog = false }, title = { Text("Log out of DeepSeek?") },
        text = { Text("This clears this app's DeepSeek session and local account chat history. Other sites' cookies are kept.") },
        confirmButton = { TextButton(onClick = { logoutDialog = false; onLogout() }) { Text("Log out") } },
        dismissButton = { TextButton(onClick = { logoutDialog = false }) { Text("Cancel") } })
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

@Composable
private fun Message(message: AccountMessage, working: Boolean) {
    if (message.user) BoxWithConstraints(Modifier.fillMaxWidth()) {
        Surface(Modifier.align(Alignment.CenterEnd).widthIn(max = maxWidth * .8f), shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.primaryContainer) {
            Text(message.text, Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onSurface)
        }
    } else Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        var expanded by rememberSaveable(message.id) { mutableStateOf(false) }
        if (message.thinking.isNotBlank()) {
            Row(Modifier.testTag("thinking-${message.id}").clickable { expanded = !expanded }.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (working && message.text.isEmpty()) "Thinking…" else "Thinking", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                AppIcon(Glyph.CHEVRON, if (expanded) "Collapse thinking" else "Expand thinking", Modifier.rotate(if (expanded) 180f else 0f))
            }
            if (expanded) Text(message.thinking, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
        if (message.text.isNotBlank()) MarkdownText(message.text)
        else if (working) Text("Connecting…", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Composer(state: AccountUiState, draft: (String) -> Unit, send: (Boolean, Boolean) -> Unit, stop: () -> Unit, attach: () -> Unit, error: (String) -> Unit) {
    var think by rememberSaveable { mutableStateOf(false) }
    var search by rememberSaveable { mutableStateOf(false) }
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
                .semantics { contentDescription = "Type a message" }, maxLines = 6, enabled = !state.busy && state.ready,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface), cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                decorationBox = { inner -> Box { if (state.draft.isEmpty()) Text("Type a message or hold to speak", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp); inner() } })
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalArrangement = Arrangement.Center) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(selected = think, onClick = { think = !think }, label = { Text("Think", fontSize = 12.sp) },
                        leadingIcon = { AppIcon(Glyph.THINK, modifier = Modifier.size(16.dp)) }, enabled = !state.busy, shape = RoundedCornerShape(18.dp), colors = pillColors,
                        border = FilterChipDefaults.filterChipBorder(enabled = !state.busy, selected = false, borderColor = MaterialTheme.colorScheme.outlineVariant))
                    FilterChip(selected = search, onClick = { search = !search }, label = { Text("Search", fontSize = 12.sp) },
                        leadingIcon = { AppIcon(Glyph.SEARCH, modifier = Modifier.size(16.dp)) }, enabled = !state.busy, shape = RoundedCornerShape(18.dp), colors = pillColors,
                        border = FilterChipDefaults.filterChipBorder(enabled = !state.busy, selected = false, borderColor = MaterialTheme.colorScheme.outlineVariant))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = attach, enabled = !state.busy && !state.importing) { OutlinedCircle { AppIcon(Glyph.PLUS, "Add attachment", Modifier.size(14.dp)) } }
                    VoiceDictation(!state.busy && state.ready, "", { draft(listOf(state.draft, it).filter(String::isNotBlank).joinToString(" ")) }, error)
                    IconButton(onClick = { if (state.busy) stop() else send(think, search) }, enabled = state.busy ||
                        (state.ready && !state.importing && !state.requiresNewChat && (state.draft.isNotBlank() || state.attachments.isNotEmpty()))) {
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
