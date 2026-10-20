package app.dsandroid.deepseek

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.dsandroid.core.deepseek.WebAttachment
import app.dsandroid.core.deepseek.WebCitation
import app.dsandroid.core.deepseek.WebStep
import app.dsandroid.core.editor.EditorLanguage
import app.dsandroid.ui.AppIcon
import app.dsandroid.ui.Glyph
import app.dsandroid.ui.LocalOfficialColors
import app.dsandroid.ui.MarkdownText
import kotlin.math.roundToInt

/** [citation:N] markers become chips on screen and plain [N] in copied or shared text. */
private val CITATION_MARKER = Regex("\\[citation:(\\d{1,3})]")
internal fun shareableText(text: String): String = CITATION_MARKER.replace(text) { "[${it.groupValues[1]}]" }

/**
 * Official top bar: menu, the chat's title (none on an empty new chat, as in the official app; the account in use shows when
 * there are several), share (open chats), new chat.
 */
@Composable
internal fun ChatTopBar(state: AccountUiState, onMenu: () -> Unit, onNew: () -> Unit, onShare: (() -> Unit)? = null) {
    Box(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp)) {
        // 48 dp buttons: Material's 40 dp icon button only reserves the extra space outside its semantics bounds.
        IconButton(onClick = onMenu, modifier = Modifier.align(Alignment.CenterStart).size(48.dp)) { AppIcon(Glyph.MENU, "Open navigation", Modifier.size(24.dp)) }
        Column(Modifier.align(Alignment.Center).padding(horizontal = if (onShare != null) 104.dp else 60.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (state.messages.isNotEmpty() || state.loadingChat) Text(state.title.ifBlank { "New chat" }, style = MaterialTheme.typography.titleMedium,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("chat-title"))
            // With several accounts, the one in use is always visible; it changes only from Settings > Accounts.
            if (state.accounts.size > 1) state.accounts.firstOrNull { it.active }?.let { active ->
                Text(active.label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("active-account"))
            }
        }
        Row(Modifier.align(Alignment.CenterEnd)) {
            if (onShare != null) IconButton(onClick = onShare, enabled = !state.busy, modifier = Modifier.size(48.dp)) { AppIcon(Glyph.SHARE, "Share chat", Modifier.size(22.dp)) }
            IconButton(onClick = onNew, enabled = !state.busy, modifier = Modifier.size(48.dp)) { AppIcon(Glyph.NEW_CHAT, "New Chat", Modifier.size(24.dp)) }
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
            SheetRow(glyph, label, "share-chat-${label.substringAfterLast(' ').lowercase()}", action)
        }
        Text("Shared chats say they come from an unofficial client and that replies are AI-generated.", Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.navigationBarsPadding().height(12.dp))
    }
}

/** Share on one reply: as text, as an image card (official "Create Image"), or as a PDF file card. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReplyShareSheet(onDismiss: () -> Unit, onText: () -> Unit, onImage: () -> Unit, onPdf: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = LocalOfficialColors.current.groupSurface) {
        Text("Share reply", Modifier.padding(horizontal = 20.dp, vertical = 4.dp), style = MaterialTheme.typography.titleMedium)
        SheetRow(Glyph.SHARE, "Share as text", "share-reply-text", onText)
        SheetRow(Glyph.PHOTO, "Create image", "share-reply-image", onImage)
        SheetRow(Glyph.EXPORT, "Export as PDF", "share-reply-pdf", onPdf)
        Spacer(Modifier.navigationBarsPadding().height(12.dp))
    }
}

@Composable
private fun SheetRow(glyph: Glyph, label: String, tag: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 52.dp).padding(horizontal = 20.dp).testTag(tag),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        AppIcon(glyph, modifier = Modifier.size(20.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
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
    onShareText: (String) -> Unit, onSources: (List<WebCitation>) -> Unit, actions: AgentActions,
    onFeedback: (String, String?) -> Unit = { _, _ -> }, onOpenLink: (String) -> Unit = {}, onShareReply: (AccountMessage) -> Unit = {}) {
    val list = androidx.compose.foundation.lazy.rememberLazyListState()
    // Follow the reply to its end (tool steps and file cards make the last item taller than the screen), and once more
    // when the turn finishes, so the final answer and its files are in view.
    var followedTurn by remember { mutableStateOf(false) }
    LaunchedEffect(state.messages.size, state.messages.lastOrNull()?.text, state.messages.lastOrNull()?.thinking, state.busy) {
        if (state.messages.isNotEmpty() && (state.busy || followedTurn)) {
            list.scrollToItem(state.messages.lastIndex)
            list.scrollBy(100_000f)
        }
        followedTurn = state.busy
    }
    LazyColumn(modifier.testTag("message-list"), state = list, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)) {
        itemsIndexed(state.messages, key = { _, message -> message.id }) { index, message ->
            val last = index == state.messages.lastIndex
            MessageItem(message, working = state.busy && last, enabled = !state.busy, regenerate = !message.user && last,
                onRegenerate = onRegenerate, onEdit = { onEdit(message) }, onShareText = onShareText, onSources = onSources, actions = actions,
                onFeedback = onFeedback, onOpenLink = onOpenLink, onShareReply = { onShareReply(message) })
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageItem(message: AccountMessage, working: Boolean, enabled: Boolean, regenerate: Boolean, onRegenerate: () -> Unit,
    onEdit: () -> Unit, onShareText: (String) -> Unit, onSources: (List<WebCitation>) -> Unit, actions: AgentActions,
    onFeedback: (String, String?) -> Unit, onOpenLink: (String) -> Unit, onShareReply: () -> Unit) {
    @Suppress("DEPRECATION") val clipboard = LocalClipboardManager.current
    if (message.user) {
        // Official: files as chips above the message, then a light bubble on the right; Copy, Edit and Share are on long press.
        var menu by remember { mutableStateOf(false) }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            Column(Modifier.align(Alignment.CenterEnd), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                message.files.forEach { FileChip(it) }
                if (message.text.isNotBlank()) Box {
                    Surface(Modifier.widthIn(max = this@BoxWithConstraints.maxWidth * .8f).clip(RoundedCornerShape(18.dp)).testTag("user-bubble-${message.id}")
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
        if (message.steps.isNotEmpty()) AgentTimeline(message.id, message.steps, working, runCode, preview, edit)
        else {
            val steps = message.timeline.ifEmpty { if (message.thinking.isNotBlank()) listOf(WebStep.Think(message.thinking, message.thinkingSeconds)) else emptyList() }
            if (steps.isNotEmpty()) ThinkingTimeline(message.id, steps, active = working && message.text.isEmpty(), seconds = message.thinkingSeconds, onSources = onSources)
            // Replies saved before the timeline existed keep their "Read N web pages" row.
            if (message.timeline.isEmpty() && message.citations.isNotEmpty()) SourcesRow(message.citations) { onSources(message.citations) }
            if (message.text.isNotBlank()) MarkdownText(message.text, citationCount = message.citations.size,
                onCitation = { number -> message.citations.getOrNull(number - 1)?.let { onOpenLink(it.url) } },
                onRunCode = runCode, onPreviewCode = preview, onEditCode = edit)
            else if (working && steps.isEmpty()) Text("Connecting…", color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall)
        }
        message.codeRuns.forEach { ToolStepRow(it) }
        message.artifacts.forEach { artifact ->
            ArtifactCard(artifact, actions.artifactFile(artifact), onOpen = { actions.openArtifact(artifact) },
                onSave = { actions.saveArtifact(artifact) }, onShare = { actions.shareArtifact(artifact) },
                onEdit = { actions.editArtifact(artifact) }.takeIf { EditorLanguage.forFileName(artifact.fileName) != null },
                onSaveAs = { actions.saveArtifactAs(artifact) })
        }
        // Official reply actions: Copy, Regenerate (latest reply), Like, Dislike, Share.
        if (!working && message.text.isNotBlank()) Row(Modifier.offset(x = (-12).dp), verticalAlignment = Alignment.CenterVertically) {
            val muted = MaterialTheme.colorScheme.onSurfaceVariant
            val accent = MaterialTheme.colorScheme.primary
            IconButton(onClick = { clipboard.setText(AnnotatedString(shareableText(message.text))) }) {
                AppIcon(Glyph.COPY, "Copy assistant message", Modifier.size(18.dp), color = muted)
            }
            if (regenerate) IconButton(onClick = onRegenerate, enabled = enabled) { AppIcon(Glyph.REGENERATE, "Regenerate response", Modifier.size(18.dp), color = muted) }
            listOf(Triple("GOOD", Glyph.LIKE, "Like"), Triple("BAD", Glyph.DISLIKE, "Dislike")).forEach { (value, glyph, label) ->
                val chosen = message.feedback == value
                IconButton(onClick = { onFeedback(message.id, if (chosen) null else value) }, enabled = enabled,
                    modifier = Modifier.testTag("${label.lowercase()}-${message.id}").semantics { selected = chosen }) {
                    AppIcon(glyph, label, Modifier.size(18.dp), color = if (chosen) accent else muted)
                }
            }
            IconButton(onClick = onShareReply, enabled = enabled, modifier = Modifier.testTag("share-${message.id}")) {
                AppIcon(Glyph.SHARE, "Share answer", Modifier.size(18.dp), color = muted)
            }
        }
    }
}

/** A file sent with a message: type colour and icon, its name, then type and size ("TXT 1.2MB"), as the official chip. */
@Composable
internal fun FileChip(file: WebAttachment) {
    val type = file.name.substringAfterLast('.', "").uppercase().take(5)
    val image = type in setOf("PNG", "JPG", "JPEG", "WEBP", "GIF", "HEIC", "BMP")
    val official = LocalOfficialColors.current
    Row(Modifier.widthIn(max = 260.dp).clip(RoundedCornerShape(12.dp)).border(1.dp, official.composerBorder, RoundedCornerShape(12.dp))
        .background(official.composerSurface).padding(horizontal = 10.dp, vertical = 8.dp).testTag("file-chip-${file.name}")
        .semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(30.dp).clip(RoundedCornerShape(7.dp)).background(if (image) Color(0xFF34A853) else Color(0xFF4D7CFE)), contentAlignment = Alignment.Center) {
            AppIcon(if (image) Glyph.PHOTO else Glyph.DOCUMENT, modifier = Modifier.size(17.dp), color = Color.White)
        }
        Column(Modifier.weight(1f, fill = false)) {
            Text(file.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
            val details = listOfNotNull(type.ifEmpty { null }, file.bytes?.let(::fileSize)).joinToString(" ")
            if (details.isNotEmpty()) Text(details, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Official size notation: "68B", "12KB", "1.2MB". */
internal fun fileSize(bytes: Long): String = when {
    bytes < 1024 -> "${bytes}B"
    bytes < 1024 * 1024 -> "${(bytes + 512) / 1024}KB"
    else -> "%.1fMB".format(java.util.Locale.ROOT, bytes / (1024.0 * 1024.0))
}

/**
 * Official thinking timeline: "Thinking" while it streams, then "Thought for N seconds" with a chevron; expanded, a grey
 * timeline whose thinking stages are dots and whose search steps read "Found N web pages" and "Read N pages" with the
 * sites' icons. A reply that searched without thinking shows its steps directly.
 */
@Composable
internal fun ThinkingTimeline(id: String, steps: List<WebStep>, active: Boolean, seconds: Double?, onSources: (List<WebCitation>) -> Unit) {
    val collapse = LocalCollapseThinking.current
    var expanded by rememberSaveable(id) { mutableStateOf(!collapse) }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val thinks = steps.any { it is WebStep.Think }
    if (thinks) {
        val label = when {
            active -> "Thinking…"
            seconds != null -> seconds.roundToInt().coerceAtLeast(1).let { "Thought for $it second${if (it == 1) "" else "s"}" }
            else -> "Thinking"
        }
        Row(Modifier.testTag("thinking-$id").clip(RoundedCornerShape(8.dp)).clickable { expanded = !expanded }.heightIn(min = 40.dp).padding(end = 4.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, color = muted, style = MaterialTheme.typography.bodyMedium)
            AppIcon(Glyph.CHEVRON, if (expanded || active) "Collapse thinking" else "Expand thinking",
                Modifier.size(18.dp).rotate(if (expanded || active) 0f else -90f), color = muted)
        }
    }
    if (!thinks || expanded || active) Column(Modifier.fillMaxWidth().testTag("timeline-$id")) {
        steps.forEachIndexed { index, step -> TimelineRow(step, last = index == steps.lastIndex, active = active && index == steps.lastIndex, onSources = onSources) }
    }
}

/** For agent reasoning steps and older callers: one thinking stage in the same timeline style. */
@Composable
internal fun ThinkingBlock(id: String, text: String, active: Boolean, seconds: Double?) =
    ThinkingTimeline(id, listOf(WebStep.Think(text, seconds)), active, seconds) {}

@Composable
private fun TimelineRow(step: WebStep, last: Boolean, active: Boolean, onSources: (List<WebCitation>) -> Unit) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val line = MaterialTheme.colorScheme.outlineVariant
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        // The gutter: a dot for thinking, an icon for a search step, joined by a thin line.
        Box(Modifier.width(22.dp).fillMaxHeight().drawBehind {
            if (!last) drawLine(line, Offset(size.width / 2, 22.dp.toPx()), Offset(size.width / 2, size.height), strokeWidth = 1.dp.toPx())
        }, contentAlignment = Alignment.TopCenter) {
            when (step) {
                is WebStep.Think -> Box(Modifier.padding(top = 8.dp).size(5.dp).clip(CircleShape).background(muted))
                is WebStep.Search -> AppIcon(Glyph.MAGNIFY, modifier = Modifier.padding(top = 2.dp).size(16.dp), color = muted)
                is WebStep.Read, is WebStep.Tool -> AppIcon(Glyph.DOCUMENT, modifier = Modifier.padding(top = 2.dp).size(16.dp), color = muted)
            }
        }
        Column(Modifier.weight(1f).padding(start = 6.dp, bottom = if (last) 4.dp else 12.dp)) {
            when (step) {
                is WebStep.Think -> Text(step.text.trim(), color = muted, style = MaterialTheme.typography.bodySmall)
                is WebStep.Search -> StepLine(if (!step.done && active) "Searching the web…" else step.label, step.results,
                    Modifier.testTag("search-step")) { onSources(step.results) }
                is WebStep.Read -> StepLine("Read ${step.pages.size} page${if (step.pages.size == 1) "" else "s"}", step.pages,
                    Modifier.testTag("read-step")) { onSources(step.pages) }
                is WebStep.Tool -> Text(step.label, color = muted, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun StepLine(label: String, pages: List<WebCitation>, modifier: Modifier, onOpen: () -> Unit) {
    Row(modifier.clip(RoundedCornerShape(8.dp)).clickable(enabled = pages.isNotEmpty(), onClick = onOpen).heightIn(min = 20.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        SiteIconStack(pages)
    }
}

/** Up to three overlapping site icons, as the official search steps show them. */
@Composable
private fun SiteIconStack(pages: List<WebCitation>) {
    val shown = pages.distinctBy { SiteIcons.host(it.url) }.take(3)
    if (shown.isEmpty()) return
    Box(Modifier.height(16.dp).width((16 + 11 * (shown.size - 1)).dp)) {
        shown.forEachIndexed { index, page -> SiteIcon(page, Modifier.offset(x = (11 * index).dp)) }
    }
}

@Composable
internal fun SiteIcon(page: WebCitation, modifier: Modifier = Modifier, size: Int = 16) {
    val context = LocalContext.current
    val host = remember(page.url) { SiteIcons.host(page.url) }
    var icon by remember(page.url) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(page.url) { icon = SiteIcons.load(context, page)?.asImageBitmap() }
    Box(modifier.size(size.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant)
        .border(1.dp, MaterialTheme.colorScheme.surface, CircleShape), contentAlignment = Alignment.Center) {
        val image = icon
        if (image != null) Image(image, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Text(host.take(1).uppercase(), fontSize = (size * 0.55f).sp, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Medium)
    }
}

/** "Read N web pages" above a Search answer saved before v1.1.0; opens the list of pages. */
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

/** The pages a Search answer found or read, numbered as the answer cites them; a tap opens one in the browser. */
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
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            SiteIcon(item, size = 14)
                            Text(item.site.ifBlank { item.url.substringAfter("://").substringBefore('/') }, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall, maxLines = 1)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.navigationBarsPadding().height(12.dp))
    }
}

/**
 * Official composer: a rounded card with the input on top and, below it, the Think and Search pills (plus Agent, this
 * app's extension) on the left and the outlined attach and voice circles on the right; voice becomes send when there is
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
    val controls = usable && !state.busy && !state.loadingChat
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
                        OutlinedRound { AppIcon(Glyph.PLUS, "Add attachment", Modifier.size(18.dp)) }
                    }
                    when {
                        state.busy -> IconButton(onClick = stop) { RoundAction(Glyph.STOP, "Stop response", enabled = true) }
                        canSend -> IconButton(onClick = { send(think, search) }, enabled = usable && !state.importing && !state.requiresNewChat && !state.loadingChat) {
                            RoundAction(Glyph.SEND, "Send message", enabled = usable && !state.importing && !state.requiresNewChat && !state.loadingChat)
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

/** The official composer's outlined 32 dp circle; it takes the button's content colour, so disabled buttons dim with it. */
@Composable
internal fun OutlinedRound(content: @Composable () -> Unit) {
    Box(Modifier.size(32.dp).border(1.4.dp, LocalContentColor.current, CircleShape), contentAlignment = Alignment.Center) { content() }
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
        .background(if (selected) colors.primaryContainer else Color.Transparent)
        .then(if (selected) Modifier else Modifier.border(1.dp, colors.outlineVariant, RoundedCornerShape(16.dp)))
        .clickable(enabled = enabled, onClickLabel = label, onClick = onClick)
        .semantics { this.selected = selected }
        .padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        AppIcon(glyph, modifier = Modifier.size(16.dp), color = content)
        Text(label, color = content, fontSize = 13.sp)
    }
}

/**
 * Official attach panel, inline under the composer: a strip of recent photos (after the person allows photo access; several
 * can be picked, then added together) above the Camera, Photo and Document tiles.
 */
@Composable
internal fun AttachmentPanel(enabled: Boolean, onPick: (String) -> Unit, onAddPhotos: (List<Uri>) -> Unit) {
    val context = LocalContext.current
    var allowed by remember { mutableStateOf(RecentPhotos.allowed(context)) }
    var photos by remember { mutableStateOf<List<Uri>>(emptyList()) }
    val selected = remember { mutableStateListOf<Uri>() }
    val access = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        allowed = granted || RecentPhotos.allowed(context)
        // Without gallery access the system photo picker still works, without any permission.
        if (!allowed) onPick("Photo")
    }
    LaunchedEffect(allowed) { if (allowed) photos = RecentPhotos.load(context) }
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp).testTag("attachment-panel"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!allowed) item(key = "allow") {
                Column(Modifier.size(76.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable(enabled = enabled) { access.launch(RecentPhotos.permission) }.padding(6.dp).testTag("recent-photos-allow"),
                    verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    AppIcon(Glyph.PHOTO, modifier = Modifier.size(22.dp))
                    Text("Recent photos", fontSize = 11.sp, textAlign = TextAlign.Center, lineHeight = 13.sp)
                }
            }
            items(photos, key = { it.toString() }) { uri ->
                PhotoThumb(uri, uri in selected, enabled) {
                    if (uri in selected) selected.remove(uri) else if (selected.size < 5) selected.add(uri)
                }
            }
        }
        if (selected.isNotEmpty()) Button(onClick = { onAddPhotos(selected.toList()); selected.clear() }, enabled = enabled,
            modifier = Modifier.fillMaxWidth().testTag("recent-photos-add"), shape = RoundedCornerShape(12.dp)) {
            Text(if (selected.size == 1) "Add 1 photo" else "Add ${selected.size} photos")
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf("Camera" to Glyph.CAMERA, "Photo" to Glyph.PHOTO, "Document" to Glyph.DOCUMENT).forEach { (label, icon) ->
                Column(Modifier.weight(1f).heightIn(min = 72.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable(enabled = enabled) { onPick(label) }.padding(vertical = 14.dp).testTag("attach-${label.lowercase()}"),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    AppIcon(icon, modifier = Modifier.size(24.dp))
                    Text(label, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        Text("Files and images are uploaded to DeepSeek after you confirm sending.", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun PhotoThumb(uri: Uri, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val context = LocalContext.current
    var image by remember(uri) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(uri) { image = RecentPhotos.thumbnail(context, uri, 240)?.asImageBitmap() }
    Box(Modifier.size(76.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
        .clickable(enabled = enabled, onClick = onClick).semantics { this.selected = selected; contentDescription = "Recent photo" }) {
        image?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        Box(Modifier.align(Alignment.TopEnd).padding(5.dp).size(20.dp).clip(CircleShape)
            .background(if (selected) MaterialTheme.colorScheme.primary else Color.Black.copy(alpha = .18f))
            .border(1.5.dp, Color.White, CircleShape), contentAlignment = Alignment.Center) {
            if (selected) AppIcon(Glyph.CHECK, modifier = Modifier.size(14.dp), color = Color.White)
        }
    }
}
