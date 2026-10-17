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
import androidx.compose.ui.res.stringResource
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
    var agentEnabled by rememberSaveable { mutableStateOf(false) }
    var editingId by rememberSaveable(state.conversationId) { mutableStateOf<String?>(null) }
    var editedText by rememberSaveable(state.conversationId) { mutableStateOf("") }
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
                Text(stringResource(R.string.greeting), style = MaterialTheme.typography.headlineSmall)
            }
        } else {
            LazyColumn(Modifier.weight(1f).widthIn(max = 840.dp).fillMaxWidth(), state = listState,
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                items(state.items, key = { it.id }) { item ->
                    when (item) {
                        is ChatItem.User -> Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
                            Surface(Modifier.widthIn(max = 600.dp).fillMaxWidth(0.86f), shape = RoundedCornerShape(18.dp),
                                color = if (MaterialTheme.colorScheme.background == Color(0xFF121212)) Color(0xFF24304F) else Color(0xFFEEF3FF)) {
                                Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    item.attachments.forEach { Text("▧ $it", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                    if (item.text.isNotEmpty()) Text(item.text, style = MaterialTheme.typography.bodyLarge)
                                }
                            }
                            Row {
                                TextButton(onClick = { vm.retryTurn(item.id, think, search, agentEnabled) }, enabled = !state.running && !state.importing) {
                                    Text(stringResource(R.string.turn_retry))
                                }
                                TextButton(onClick = { editingId = item.id; editedText = item.text }, enabled = !state.running && !state.importing) {
                                    Text(stringResource(R.string.turn_edit))
                                }
                            }
                        }
                        is ChatItem.Assistant -> Column {
                            AssistantTurn(item, working = state.running && item.id == state.items.lastOrNull()?.id, openArtifact, settings.collapseThinking)
                            val user = state.items.takeWhile { it.id != item.id }.filterIsInstance<ChatItem.User>().lastOrNull()
                            if (user != null) TextButton(onClick = { vm.restartTurn(user.id, null, think, search, agentEnabled) }, enabled = !state.running && !state.importing) {
                                Text(stringResource(R.string.turn_regenerate))
                            }
                        }
                    }
                }
                item(key = "conversation-tail") { Spacer(Modifier.height(1.dp)) }
            }
        }
        Composer(draft, { draft = it }, think, { think = it }, search, { search = it }, agentEnabled, { agentEnabled = it }, state, settings.supportsVision(think),
            { attachSheet = true }, { if (vm.send(draft, think, search, agentEnabled)) draft = "" }, vm::stop, vm::removeAttachment,
            settings.audioLanguage, { spoken -> draft = listOf(draft, spoken).filter { it.isNotBlank() }.joinToString(" ") }, vm::showError)
    }
    if (editingId != null) AlertDialog(onDismissRequest = { editingId = null },
        title = { Text(stringResource(R.string.turn_edit_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.turn_edit_warning))
                OutlinedTextField(editedText, { editedText = it }, modifier = Modifier.fillMaxWidth(), maxLines = 8,
                    label = { Text(stringResource(R.string.message_placeholder)) })
            }
        },
        confirmButton = { TextButton(onClick = {
            editingId?.let { if (vm.restartTurn(it, editedText, think, search, agentEnabled)) editingId = null }
        }) { Text(stringResource(R.string.turn_resend)) } },
        dismissButton = { TextButton(onClick = { editingId = null }) { Text(stringResource(R.string.cancel)) } })
    if (attachSheet) ModalBottomSheet(onDismissRequest = { attachSheet = false }) {
        Text(stringResource(R.string.attach_title), Modifier.padding(horizontal = 24.dp, vertical = 8.dp), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.attachment_limits), Modifier.padding(horizontal = 24.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth().padding(20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            AttachmentTile(stringResource(R.string.camera), Glyph.CAMERA, Modifier.weight(1f)) {
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
                    vm.showError(context.getString(R.string.camera_unavailable))
                }
            }
            AttachmentTile(stringResource(R.string.photo), Glyph.PHOTO, Modifier.weight(1f)) {
                attachSheet = false
                try { photo.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
                catch (_: Exception) { vm.showError(context.getString(R.string.photo_unavailable)) }
            }
            AttachmentTile(stringResource(R.string.document), Glyph.DOCUMENT, Modifier.weight(1f)) {
                attachSheet = false
                try { documents.launch(arrayOf("*/*")) }
                catch (_: Exception) { vm.showError(context.getString(R.string.document_unavailable)) }
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
    agentEnabled: Boolean, setAgent: (Boolean) -> Unit, state: ChatUiState, vision: Boolean, attach: () -> Unit, send: () -> Unit, stop: () -> Unit, remove: (String) -> Unit,
    audioLanguage: String, dictate: (String) -> Unit, report: (String) -> Unit) {
    val enabled = state.settingsReady && !state.saving && !state.signingIn && !state.running && !state.importing
    val placeholder = stringResource(R.string.message_placeholder)
    Surface(Modifier.widthIn(max = 840.dp).fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        shape = RoundedCornerShape(24.dp), color = if (MaterialTheme.colorScheme.background == Color(0xFF121212)) Color(0xFF1E1E1E) else Color.White,
        shadowElevation = 3.dp, border = androidx.compose.foundation.BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            if (state.attachments.isNotEmpty()) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                state.attachments.forEach { upload ->
                    InputChip(selected = false, onClick = {}, label = {
                        Column(Modifier.widthIn(max = 230.dp)) {
                            Text(upload.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(if (upload.kind == AttachmentKind.IMAGE) {
                                if (vision) stringResource(R.string.image_ready) else stringResource(R.string.image_unreadable)
                            } else stringResource(R.string.attachment_chars, upload.text.length) + if (upload.text.isBlank()) " · " + stringResource(R.string.no_text) else "", style = MaterialTheme.typography.labelSmall)
                        }
                    }, trailingIcon = {
                        IconButton(onClick = { remove(upload.id) }, enabled = !state.running, modifier = Modifier.size(36.dp)) { AppIcon(Glyph.CLOSE, stringResource(R.string.remove_attachment, upload.name), Modifier.size(16.dp)) }
                    })
                }
            }
            if (state.importing) Text(stringResource(R.string.reading_attachments), Modifier.padding(8.dp), style = MaterialTheme.typography.labelSmall)
            BasicTextField(draft, edit, Modifier.fillMaxWidth().heightIn(min = 52.dp, max = 144.dp).padding(horizontal = 8.dp, vertical = 12.dp)
                .semantics { contentDescription = placeholder },
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary), maxLines = 6,
                decorationBox = { field -> Box { if (draft.isEmpty()) Text(stringResource(R.string.message_placeholder), color = MaterialTheme.colorScheme.onSurfaceVariant); field() } })
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !agentEnabled, onClick = { setAgent(false) }, enabled = enabled,
                    label = { Text(stringResource(R.string.mode_chat)) })
                FilterChip(selected = agentEnabled, onClick = { setAgent(true) }, enabled = enabled,
                    label = { Text(stringResource(R.string.mode_agent)) })
                ComposerChip(stringResource(R.string.think), Glyph.THINK, think, enabled) { setThink(!think) }
                ComposerChip(stringResource(R.string.search), Glyph.SEARCH, search && agentEnabled, enabled && agentEnabled) { setSearch(!search) }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(if (agentEnabled) R.string.mode_description else R.string.mode_chat), Modifier.weight(1f),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                VoiceDictation(enabled, audioLanguage, dictate, report)
                IconButton(onClick = attach, enabled = enabled, modifier = Modifier.size(44.dp).border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)) {
                    AppIcon(Glyph.PLUS, stringResource(R.string.attach_files))
                }
                FilledIconButton(onClick = if (state.running) stop else send, modifier = Modifier.size(44.dp),
                    enabled = state.running || (enabled && (draft.isNotBlank() || state.attachments.isNotEmpty())),
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = AccentBlue, contentColor = Color.White)) {
                    AppIcon(if (state.running) Glyph.STOP else Glyph.SEND, if (state.running) stringResource(R.string.stop_generating) else stringResource(R.string.send_message))
                }
            }
        }
    }
}

@Composable
private fun ComposerChip(label: String, glyph: Glyph, selected: Boolean, enabled: Boolean, click: () -> Unit) {
    Surface(onClick = click, enabled = enabled, shape = CircleShape,
        color = if(selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = if(selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant) {
        Row(Modifier.padding(horizontal = 6.dp).heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            AppIcon(glyph, modifier = Modifier.size(14.dp))
            Text(label, style = MaterialTheme.typography.labelSmall)
        }
    }
}
