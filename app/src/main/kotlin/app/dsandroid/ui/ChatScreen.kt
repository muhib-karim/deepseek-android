package app.dsandroid.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import app.dsandroid.R
import app.dsandroid.core.attachments.AttachmentKind
import app.dsandroid.core.tools.Artifact
import app.dsandroid.data.AppSettings
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.collect

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(state: ChatUiState, settings: AppSettings, modifier: Modifier, vm: ChatViewModel, openArtifact: (Artifact) -> Unit) {
    var draft by rememberSaveable(state.conversationId) { mutableStateOf("") }
    var think by rememberSaveable { mutableStateOf(false) }
    var search by rememberSaveable { mutableStateOf(false) }
    var attachSheet by remember { mutableStateOf(false) }
    var cameraName by rememberSaveable { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    fun cameraFile(name: String) = File(File(context.cacheDir, "camera"), name)
    fun cameraUri(name: String): Uri = FileProvider.getUriForFile(context, "${context.packageName}.artifacts", cameraFile(name))
    val documents = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { vm.addAttachments(it) }
    val photo = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) vm.addAttachments(listOf(uri)) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val name = cameraName
        cameraName = null
        if (name != null) {
            if (success) vm.addAttachments(listOf(cameraUri(name)))
            else cameraFile(name).delete()
        }
    }
    val listState = rememberLazyListState()
    var followTail by rememberSaveable(state.conversationId) { mutableStateOf(true) }
    var scrollingToEnd by remember { mutableStateOf(false) }
    LaunchedEffect(listState, state.conversationId) {
        snapshotFlow { Triple(listState.isScrollInProgress, listState.canScrollForward, scrollingToEnd) }.collect { (scrolling, canForward, automatic) ->
            if (scrolling && !automatic) followTail = !canForward
        }
    }
    val last = state.items.lastOrNull() as? ChatItem.Assistant
    LaunchedEffect(state.conversationId, state.items.size, last?.steps, last?.artifacts) {
        if (state.items.isNotEmpty() && followTail && !listState.isScrollInProgress) {
            scrollingToEnd = true
            try { listState.scrollToItem(state.items.size) }
            finally { scrollingToEnd = false }
        }
    }
    Column(modifier.fillMaxSize().imePadding(), horizontalAlignment = Alignment.CenterHorizontally) {
        if (state.items.isEmpty()) {
            Column(Modifier.weight(1f).widthIn(max = 640.dp).fillMaxWidth().padding(horizontal = 24.dp),
                verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Image(painterResource(R.drawable.ic_mark), contentDescription = null, modifier = Modifier.size(90.dp))
                Text("How can I help you?", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(28.dp))
                listOf("Make a one-page PDF summary of…", "Draw an SVG logo for…", "Turn this into a CSV…").forEach { example ->
                    TextButton(onClick = { draft = example.removeSuffix("…") }, modifier = Modifier.fillMaxWidth()) {
                        Text(example, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        } else {
            LazyColumn(Modifier.weight(1f).widthIn(max = 840.dp).fillMaxWidth(), state = listState,
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                items(state.items, key = { it.id }) { item ->
                    when (item) {
                        is ChatItem.User -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            Surface(Modifier.widthIn(max = 600.dp).fillMaxWidth(0.86f), shape = RoundedCornerShape(18.dp),
                                color = if (isSystemInDarkTheme()) Color(0xFF24304F) else Color(0xFFEEF3FF)) {
                                Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    item.attachments.forEach { Text("▧ $it", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                    if (item.text.isNotEmpty()) Text(item.text, style = MaterialTheme.typography.bodyLarge)
                                }
                            }
                        }
                        is ChatItem.Assistant -> AssistantTurn(item, working = state.running && item.id == state.items.lastOrNull()?.id, openArtifact)
                    }
                }
                item(key = "conversation-tail") { Spacer(Modifier.height(1.dp)) }
            }
        }
        Composer(draft, { draft = it }, think, { think = it }, search, { search = it }, state, settings.supportsVision(think),
            { attachSheet = true }, { if (vm.send(draft, think, search)) draft = "" }, vm::stop, vm::removeAttachment)
    }
    if (attachSheet) ModalBottomSheet(onDismissRequest = { attachSheet = false }) {
        Text("Add to your message", Modifier.padding(horizontal = 24.dp, vertical = 8.dp), style = MaterialTheme.typography.titleMedium)
        Row(Modifier.fillMaxWidth().padding(20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            AttachmentTile("Camera", Glyph.CAMERA, Modifier.weight(1f)) {
                attachSheet = false
                try {
                    val name = "${UUID.randomUUID()}.jpg"
                    val file = cameraFile(name)
                    check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs())
                    check(file.createNewFile())
                    cameraName = name
                    camera.launch(cameraUri(name))
                } catch (_: Exception) {
                    cameraName?.let { cameraFile(it).delete() }; cameraName = null
                    vm.showError("No camera app is available. Try Photo or Document.")
                }
            }
            AttachmentTile("Photo", Glyph.PHOTO, Modifier.weight(1f)) {
                attachSheet = false
                try { photo.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
                catch (_: Exception) { vm.showError("The photo picker is unavailable. Try Document.") }
            }
            AttachmentTile("Document", Glyph.DOCUMENT, Modifier.weight(1f)) {
                attachSheet = false
                try { documents.launch(arrayOf("*/*")) }
                catch (_: Exception) { vm.showError("The document picker is unavailable.") }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun AttachmentTile(label: String, glyph: Glyph, modifier: Modifier, click: () -> Unit) {
    Surface(modifier.clip(RoundedCornerShape(20.dp)).clickable(onClick = click), color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            AppIcon(glyph, modifier = Modifier.size(28.dp))
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun Composer(draft: String, edit: (String) -> Unit, think: Boolean, setThink: (Boolean) -> Unit, search: Boolean, setSearch: (Boolean) -> Unit,
    state: ChatUiState, vision: Boolean, attach: () -> Unit, send: () -> Unit, stop: () -> Unit, remove: (String) -> Unit) {
    val enabled = state.settingsReady && !state.saving && !state.signingIn && !state.running && !state.importing
    Surface(Modifier.widthIn(max = 840.dp).fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        shape = RoundedCornerShape(24.dp), color = if (isSystemInDarkTheme()) Color(0xFF1E1E1E) else Color.White,
        shadowElevation = 3.dp, border = androidx.compose.foundation.BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            if (state.attachments.isNotEmpty()) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                state.attachments.forEach { upload ->
                    InputChip(selected = false, onClick = {}, label = {
                        Column(Modifier.widthIn(max = 230.dp)) {
                            Text(upload.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(if (upload.kind == AttachmentKind.IMAGE) {
                                if (vision) "Image · ready" else "image not readable by this model"
                            } else "${upload.text.length} chars" + if (upload.text.isBlank()) " · no text found" else "", style = MaterialTheme.typography.labelSmall)
                        }
                    }, trailingIcon = {
                        IconButton(onClick = { remove(upload.id) }, enabled = !state.running, modifier = Modifier.size(36.dp)) { AppIcon(Glyph.CLOSE, "Remove ${upload.name}", Modifier.size(16.dp)) }
                    })
                }
            }
            if (state.importing) Text("Reading attachments…", Modifier.padding(8.dp), style = MaterialTheme.typography.labelSmall)
            BasicTextField(draft, edit, Modifier.fillMaxWidth().heightIn(min = 52.dp, max = 144.dp).padding(horizontal = 8.dp, vertical = 12.dp)
                .semantics { contentDescription = "Type a message" },
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary), maxLines = 6,
                decorationBox = { field -> Box { if (draft.isEmpty()) Text("Type a message", color = MaterialTheme.colorScheme.onSurfaceVariant); field() } })
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ComposerChip("Think", Glyph.THINK, think, enabled) { setThink(!think) }
                ComposerChip("Search", Glyph.SEARCH, search, enabled) { setSearch(!search) }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = attach, enabled = enabled, modifier = Modifier.size(44.dp).border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)) {
                    AppIcon(Glyph.PLUS, "Attach files")
                }
                FilledIconButton(onClick = if (state.running) stop else send, modifier = Modifier.size(44.dp),
                    enabled = state.running || (enabled && (draft.isNotBlank() || state.attachments.isNotEmpty())),
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = AccentBlue, contentColor = Color.White)) {
                    AppIcon(if (state.running) Glyph.STOP else Glyph.SEND, if (state.running) "Stop generating" else "Send message")
                }
            }
        }
    }
}

@Composable
private fun ComposerChip(label: String, glyph: Glyph, selected: Boolean, enabled: Boolean, click: () -> Unit) {
    FilterChip(selected = selected, onClick = click, enabled = enabled, label = { Text(label, style = MaterialTheme.typography.labelMedium) },
        leadingIcon = { AppIcon(glyph, modifier = Modifier.size(16.dp)) }, shape = CircleShape,
        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer, selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimaryContainer))
}
