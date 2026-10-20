package app.dsandroid.deepseek

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.dsandroid.core.deepseek.WebCitation
import app.dsandroid.core.editor.EditorLanguage
import app.dsandroid.ui.AppIcon
import app.dsandroid.ui.Glyph
import app.dsandroid.ui.LocalOfficialColors
import app.dsandroid.ui.MarkdownText
import kotlin.math.roundToInt

/** Official top bar: menu, the chat's title (and the account in use when there are several), share (open chats), new chat. */
@Composable
internal fun ChatTopBar(state: AccountUiState, onMenu: () -> Unit, onNew: () -> Unit, onShare: (() -> Unit)? = null) {
    Box(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp)) {
        IconButton(onClick = onMenu, modifier = Modifier.align(Alignment.CenterStart)) { AppIcon(Glyph.MENU, "Open navigation", Modifier.size(24.dp)) }
        Column(Modifier.align(Alignment.Center).padding(horizontal = if (onShare != null) 104.dp else 60.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(state.title.ifBlank { "New chat" }, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            // With several accounts, the one in use is always visible; it changes only from Settings > Accounts.
            if (state.accounts.size > 1) state.accounts.firstOrNull { it.active }?.let { active ->
                Text(active.label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("active-account"))
            }
        }
        Row(Modifier.align(Alignment.CenterEnd)) {
            if (onShare != null) IconButton(onClick = onShare, enabled = !state.busy) { AppIcon(Glyph.SHARE, "Share chat", Modifier.size(22.dp)) }
            IconButton(onClick = onNew, enabled = !state.busy) { AppIcon(Glyph.NEW_CHAT, "New Chat", Modifier.size(24.dp)) }
        }
    }
}

/** Share chat: the conversation as text (to any app) or as a PDF file card in the chat. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ShareChatSheet(onDismiss: () -> Unit, onShareText: () -> Unit, onExportPdf: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = LocalOfficialColors.current.groupSurface) {
        Text("Share chat", Modifier.padding(horizontal = 20.dp, vertical = 4.dp), style = MaterialTheme.typography.titleMedium)
        listOf(Triple(Glyph.SHARE, "Share as text", onShareText), Triple(Glyph.EXPORT, "Export as PDF", onExportPdf)).forEach { (glyph, label, action) ->
            Row(Modifier.fillMaxWidth().clickable(onClick = action).heightIn(min = 52.dp).padding(horizontal = 20.dp)
                .testTag("share-chat-${label.substringAfterLast(' ').lowercase()}"), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                AppIcon(glyph, modifier = Modifier.size(20.dp))
                Text(label, style = MaterialTheme.typography.bodyLarge)
            }
        }
        Text("Shared chats say they come from an unofficial client and that replies are AI-generated.", Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.navigationBarsPadding().height(12.dp))
    }
}

/** An empty chat: the mark above the greeting, centred. */
@Composable
internal fun EmptyChat(modifier: Modifier = Modifier) {
    Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        AppMark(Modifier.size(52.dp))
        Spacer(Modifier.height(16.dp))
        Text("How can I help you?", style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
    }
}

/** The conversation, newest at the bottom; follows the reply while it streams. */
@Composable
internal fun MessageList(state: AccountUiState, modifier: Modifier, onRegenerate: () -> Unit, onEdit: (AccountMessage) -> Unit,
    onShareText: (String) -> Unit, onSources: (List<WebCitation>) -> Unit, actions: AgentActions) {
    val list = androidx.compose.foundation.lazy.rememberLazyListState()
    LaunchedEffect(state.messages.lastOrNull()?.text, state.messages.lastOrNull()?.thinking) {
        if (state.busy && state.messages.isNotEmpty()) list.scrollToItem(state.messages.lastIndex)
    }
    LazyColumn(modifier.testTag("message-list"), state = list, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)) {
        itemsIndexed(state.messages, key = { _, message -> message.id }) { index, message ->
            val last = index == state.messages.lastIndex
            MessageItem(message, working = state.busy && last, enabled = !state.busy, regenerate = !message.user && last,
                onRegenerate = onRegenerate, onEdit = { onEdit(message) }, onShareText = onShareText, onSources = onSources, actions = actions)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageItem(message: AccountMessage, working: Boolean, enabled: Boolean, regenerate: Boolean, onRegenerate: () -> Unit,
    onEdit: () -> Unit, onShareText: (String) -> Unit, onSources: (List<WebCitation>) -> Unit, actions: AgentActions) {
    @Suppress("DEPRECATION") val clipboard = LocalClipboardManager.current
    if (message.user) {
        // Official: the user's message is a light bubble on the right; Copy, Edit and Share are on long press.
        var menu by remember { mutableStateOf(false) }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            Box(Modifier.align(Alignment.CenterEnd)) {
                Surface(Modifier.widthIn(max = maxWidth * .8f).clip(RoundedCornerShape(18.dp)).testTag("user-bubble-${message.id}")
                    .combinedClickable(onClick = {}, onLongClick = { menu = true }, onLongClickLabel = "Message options")
                    .semantics {
                        customActions = listOf(
                            CustomAccessibilityAction("Copy") { clipboard.setText(AnnotatedString(message.text)); true },
                            CustomAccessibilityAction("Edit") { if (enabled) onEdit(); enabled },
                        )
                    }, color = LocalOfficialColors.current.userBubble, shape = RoundedCornerShape(18.dp)) {
                    Text(message.text, Modifier.padding(horizontal = 14.dp, vertical = 10.dp), style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface)
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Copy") }, leadingIcon = { AppIcon(Glyph.COPY, modifier = Modifier.size(18.dp)) },
                        onClick = { clipboard.setText(AnnotatedString(message.text)); menu = false },
                        modifier = Modifier.semantics { contentDescription = "Copy user message" })
                    DropdownMenuItem(text = { Text("Edit") }, leadingIcon = { AppIcon(Glyph.EDIT, modifier = Modifier.size(18.dp)) },
                        onClick = { menu = false; onEdit() }, enabled = enabled,
                        modifier = Modifier.testTag("edit-${message.id}").semantics { contentDescription = "Edit message" })
                    DropdownMenuItem(text = { Text("Share") }, leadingIcon = { AppIcon(Glyph.SHARE, modifier = Modifier.size(18.dp)) },
                        onClick = { menu = false; onShareText(message.text) })
                }
            }
        }
        return
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        val onRun: (String) -> Unit = { code -> actions.runCode(message.id, code) }
        val runCode = onRun.takeIf { enabled }
        // Finished code blocks open in the artifacts panel (HTML, SVG, Markdown) or in the editor (other languages).
        val onPreview: (String, String) -> Unit = { language, code -> actions.previewCode(message.id, message.revisionOf, language, code) }
        val onEditCode: (String, String) -> Unit = { language, code -> actions.editCode(language, code) }
        val preview = onPreview.takeIf { !working }
        val edit = onEditCode.takeIf { !working }
        if (message.citations.isNotEmpty()) SourcesRow(message.citations) { onSources(message.citations) }
        if (message.steps.isNotEmpty()) AgentTimeline(message.id, message.steps, working, runCode, preview, edit)
        else {
            if (message.thinking.isNotBlank()) ThinkingBlock(message.id, message.thinking, working && message.text.isEmpty(), message.thinkingSeconds)
            if (message.text.isNotBlank()) MarkdownText(message.text, onRunCode = runCode, onPreviewCode = preview, onEditCode = edit)
            else if (working && message.thinking.isEmpty()) Text("Connecting…", color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall)
        }
        message.codeRuns.forEach { ToolStepRow(it) }
        message.artifacts.forEach { artifact ->
            ArtifactCard(artifact, actions.artifactFile(artifact), onOpen = { actions.openArtifact(artifact) },
                onSave = { actions.saveArtifact(artifact) }, onShare = { actions.shareArtifact(artifact) },
                onEdit = { actions.editArtifact(artifact) }.takeIf { EditorLanguage.forFileName(artifact.fileName) != null })
        }
        if (!working && message.text.isNotBlank()) Row(Modifier.offset(x = (-12).dp), verticalAlignment = Alignment.CenterVertically) {
            val muted = MaterialTheme.colorScheme.onSurfaceVariant
            IconButton(onClick = { clipboard.setText(AnnotatedString(message.text)) }) {
                AppIcon(Glyph.COPY, "Copy assistant message", Modifier.size(18.dp), color = muted)
            }
            if (regenerate) IconButton(onClick = onRegenerate, enabled = enabled) { AppIcon(Glyph.REGENERATE, "Regenerate response", Modifier.size(18.dp), color = muted) }
            IconButton(onClick = { onShareText(message.text) }) { AppIcon(Glyph.SHARE, "Share answer", Modifier.size(18.dp), color = muted) }
            IconButton(onClick = { actions.exportPdf(message.id) }, enabled = enabled, modifier = Modifier.testTag("export-${message.id}")) {
                AppIcon(Glyph.EXPORT, "Export as PDF", Modifier.size(18.dp), color = muted)
            }
        }
    }
}

/** Official thinking block: "Thinking…" while it streams, then "Thought for N seconds"; collapsed once finished. */
@Composable
internal fun ThinkingBlock(id: String, text: String, active: Boolean, seconds: Double?) {
    val collapse = LocalCollapseThinking.current
    var expanded by rememberSaveable(id) { mutableStateOf(!collapse) }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val label = when {
        active -> "Thinking…"
        seconds != null -> seconds.roundToInt().coerceAtLeast(1).let { "Thought for $it second${if (it == 1) "" else "s"}" }
        else -> "Thinking"
    }
    Row(Modifier.testTag("thinking-$id").clip(RoundedCornerShape(8.dp)).clickable { expanded = !expanded }.heightIn(min = 40.dp).padding(end = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        AppIcon(Glyph.THINK, modifier = Modifier.size(16.dp), color = muted)
        Text(label, color = muted, style = MaterialTheme.typography.bodySmall)
        AppIcon(Glyph.CHEVRON, if (expanded) "Collapse thinking" else "Expand thinking", Modifier.size(18.dp).rotate(if (expanded) 180f else 0f), color = muted)
    }
    if (expanded || active) Row(Modifier.height(IntrinsicSize.Min).padding(start = 7.dp)) {
        Box(Modifier.width(2.dp).fillMaxHeight().background(MaterialTheme.colorScheme.outlineVariant))
        Text(text, Modifier.padding(start = 12.dp), color = muted, style = MaterialTheme.typography.bodySmall)
    }
}

/** "Read N web pages" above a Search answer; opens the list of pages. */
@Composable
internal fun SourcesRow(citations: List<WebCitation>, onOpen: () -> Unit) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(Modifier.clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceVariant).clickable(onClick = onOpen)
        .heightIn(min = 36.dp).padding(horizontal = 12.dp).testTag("search-sources"), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        AppIcon(Glyph.SEARCH, modifier = Modifier.size(16.dp), color = muted)
        Text("Read ${citations.size} web page${if (citations.size == 1) "" else "s"}", color = muted, style = MaterialTheme.typography.bodySmall)
        AppIcon(Glyph.CHEVRON, modifier = Modifier.size(16.dp).rotate(-90f), color = muted)
    }
}

/** The pages a Search answer read, numbered as the answer cites them; a tap opens one in the browser. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SourcesSheet(citations: List<WebCitation>, onDismiss: () -> Unit, onOpen: (String) -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = LocalOfficialColors.current.groupSurface) {
        Text("Search results", Modifier.padding(horizontal = 20.dp, vertical = 4.dp), style = MaterialTheme.typography.titleMedium)
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp)) {
            itemsIndexed(citations, key = { _, item -> item.url }) { index, item ->
                Row(Modifier.fillMaxWidth().clickable { onOpen(item.url) }.padding(horizontal = 20.dp, vertical = 10.dp).testTag("source-${index + 1}"),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("${index + 1}", Modifier.width(20.dp), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                    Column(Modifier.weight(1f)) {
                        Text(item.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                        Text(item.url.substringAfter("://").substringBefore('/'), color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall, maxLines = 1)
                    }
                }
            }
        }
        Spacer(Modifier.navigationBarsPadding().height(12.dp))
    }
}

/**
 * Official composer: a rounded card with the input on top and, below it, the Think and Search pills (plus Agent, this
 * app's extension) on the left and attach and microphone on the right; the microphone becomes send when there is
 * something to send, and stop while a reply streams.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ChatComposer(state: AccountUiState, draft: (String) -> Unit, send: (Boolean, Boolean) -> Unit, stop: () -> Unit, attach: () -> Unit,
    error: (String) -> Unit, speechInput: AccountSpeechInput?, voiceTrigger: Int, onVoiceHandled: () -> Unit, onAgent: (Boolean) -> Unit) {
    var think by rememberSaveable { mutableStateOf(false) }
    var search by rememberSaveable { mutableStateOf(false) }
    val official = LocalOfficialColors.current
    // The chat page exists only for a confirmed session; this stays as a second line of defence.
    val usable = state.signedIn && state.ready
    val controls = usable && !state.busy
    val canSend = state.draft.isNotBlank() || state.attachments.isNotEmpty()
    Surface(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 12.dp), shape = RoundedCornerShape(22.dp),
        color = official.composerSurface, border = BorderStroke(1.dp, official.composerBorder), shadowElevation = 2.dp) {
        Column(Modifier.padding(start = 14.dp, end = 6.dp, top = 12.dp, bottom = 4.dp)) {
            BasicTextField(state.draft, draft, Modifier.fillMaxWidth().padding(end = 8.dp).heightIn(min = 24.dp).testTag("account-composer")
                .semantics { contentDescription = "Type a message" }, maxLines = 6, enabled = controls,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                decorationBox = { inner ->
                    Box {
                        if (state.draft.isEmpty()) Text("Type a message or hold to speak", color = official.placeholder, style = MaterialTheme.typography.bodyLarge)
                        inner()
                    }
                })
            FlowRow(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalArrangement = Arrangement.Center) {
                Row(Modifier.padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Pill("Think", Glyph.THINK, think, controls) { think = !think }
                    Pill("Search", Glyph.SEARCH, search, controls) { search = !search }
                    Pill("Agent", Glyph.TOOL, state.agent, controls, Modifier.testTag("agent-toggle")
                        .semantics { contentDescription = if (state.agent) "Agent mode on" else "Agent mode off" }) { onAgent(!state.agent) }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = attach, enabled = controls && !state.importing) {
                        Box(Modifier.size(32.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                            AppIcon(Glyph.PLUS, "Add attachment", Modifier.size(18.dp))
                        }
                    }
                    when {
                        state.busy -> IconButton(onClick = stop) { RoundAction(Glyph.STOP, "Stop response", enabled = true) }
                        canSend -> IconButton(onClick = { send(think, search) }, enabled = usable && !state.importing && !state.requiresNewChat) {
                            RoundAction(Glyph.SEND, "Send message", enabled = usable && !state.importing && !state.requiresNewChat)
                        }
                        else -> key(state.conversationId) {
                            AccountDictation(controls, { draft(listOf(state.draft, it).filter(String::isNotBlank).joinToString(" ")) }, error,
                                speechInput, voiceTrigger, onVoiceHandled, language = state.audioLanguage)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RoundAction(glyph: Glyph, description: String, enabled: Boolean) {
    Surface(shape = CircleShape, color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = if (enabled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant) {
        AppIcon(glyph, description, Modifier.padding(7.dp).size(18.dp))
    }
}

/** Think / Search pill: outlined when off, light-blue fill with accent text when on. */
@Composable
private fun Pill(label: String, glyph: Glyph, selected: Boolean, enabled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val content = when {
        !enabled -> colors.onSurfaceVariant.copy(alpha = .5f)
        selected -> colors.onPrimaryContainer
        else -> colors.onSurface
    }
    Row(modifier.height(32.dp).clip(RoundedCornerShape(16.dp))
        .background(if (selected) colors.primaryContainer else androidx.compose.ui.graphics.Color.Transparent)
        .then(if (selected) Modifier else Modifier.border(1.dp, colors.outlineVariant, RoundedCornerShape(16.dp)))
        .clickable(enabled = enabled, onClickLabel = label, onClick = onClick)
        .semantics { this.selected = selected }
        .padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        AppIcon(glyph, modifier = Modifier.size(16.dp), color = content)
        Text(label, color = content, fontSize = 13.sp)
    }
}

/** Attach: Camera, Photo and Document tiles, as the official sheet shows them. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AttachmentSheet(enabled: Boolean, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = LocalOfficialColors.current.groupSurface) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf("Camera" to Glyph.CAMERA, "Photo" to Glyph.PHOTO, "Document" to Glyph.DOCUMENT).forEach { (label, icon) ->
                Surface(Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).clickable(enabled = enabled) { onPick(label) },
                    color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(14.dp)) {
                    Column(Modifier.fillMaxWidth().padding(vertical = 18.dp).heightIn(min = 64.dp), horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        AppIcon(icon, modifier = Modifier.size(26.dp))
                        Text(label, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
        Text("Files and images are uploaded to DeepSeek after you confirm sending.", Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.navigationBarsPadding().height(12.dp))
    }
}
