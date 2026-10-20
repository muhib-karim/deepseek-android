package app.dsandroid.workbench

import android.graphics.Bitmap
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.dsandroid.core.editor.EditorLanguage
import app.dsandroid.core.workbench.ArtifactKind
import app.dsandroid.core.workbench.WorkbenchArtifact
import app.dsandroid.sandbox.SandboxWebViews
import app.dsandroid.tools.AndroidSvgRasterizer
import app.dsandroid.ui.AppIcon
import app.dsandroid.ui.Glyph
import app.dsandroid.ui.MarkdownText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

fun ArtifactKind.editorLanguage(): EditorLanguage = when (this) {
    ArtifactKind.HTML -> EditorLanguage.HTML
    ArtifactKind.SVG -> EditorLanguage.XML
    ArtifactKind.MARKDOWN -> EditorLanguage.MARKDOWN
}

/** "v3 · DeepSeek revision · +12 -3 lines": the selected version, where it came from and what it changed. */
fun versionLine(artifact: WorkbenchArtifact, number: Int): String {
    val version = artifact.version(number) ?: return ""
    val change = artifact.changeFromPrevious(number)?.summary()
    return listOfNotNull("v$number", version.origin.label, change).joinToString(" · ")
}

/**
 * Live preview and source of one artifact with its versions. HTML runs in a disposable sandboxed WebView
 * (JavaScript for the artifact only, no file or content access, an opaque blank origin, network only after
 * "Allow network for this preview"); SVG is drawn with AndroidSVG; Markdown uses the app's renderer.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ArtifactPanel(
    pane: WorkbenchPane.Panel, textScale: Float, chatBusy: Boolean,
    draft: (String) -> String?, saveDraft: (String, String?) -> Unit,
    onClose: () -> Unit, onRun: (String) -> Unit, onAsk: (Int, String, String) -> Unit,
    onSaveAs: (String, String) -> Unit, onShare: (String, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val artifact = pane.artifact
    var selected by rememberSaveable(artifact.id) { mutableIntStateOf(artifact.latest.number) }
    val version = artifact.version(selected) ?: artifact.latest
    val editor = remember(artifact.id) {
        EditorState(draft(artifact.id) ?: version.source, artifact.kind.editorLanguage()) { saveDraft(artifact.id, it) }
    }
    // Every new version (a Run with edits or DeepSeek's revision) is selected and shown.
    var seenLatest by rememberSaveable(artifact.id) { mutableIntStateOf(artifact.latest.number) }
    LaunchedEffect(artifact.latest.number) {
        if (artifact.latest.number != seenLatest) {
            seenLatest = artifact.latest.number
            selected = artifact.latest.number
            editor.reset(artifact.latest.source)
            saveDraft(artifact.id, null)
        }
    }
    var sourceTab by rememberSaveable(artifact.id) { mutableStateOf(false) }
    var renderKey by remember(artifact.id) { mutableIntStateOf(0) }
    var network by remember(artifact.id, selected) { mutableStateOf(false) }
    var confirmNetwork by remember { mutableStateOf(false) }
    var asking by remember { mutableStateOf(false) }
    var instruction by rememberSaveable(artifact.id) { mutableStateOf("") }
    var versionsOpen by remember { mutableStateOf(false) }
    var moreOpen by remember { mutableStateOf(false) }
    val dirty = editor.text != version.source
    val fileName = artifact.title.replace(Regex("[^A-Za-z0-9._ -]"), "").trim().ifEmpty { "artifact" }.take(60) + "." + artifact.kind.extension
    BackHandler(onBack = onClose)

    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).statusBarsPadding().navigationBarsPadding().imePadding()
        .testTag("artifact-panel")) {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onClose) { Text("Close") }
            Column(Modifier.weight(1f).padding(horizontal = 4.dp)) {
                Text(artifact.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(artifact.kind.label + if (artifact.filePath != null) " · file" else "", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Box {
                OutlinedButton(onClick = { versionsOpen = true }, enabled = !dirty, modifier = Modifier.testTag("version-picker")
                    .semantics { contentDescription = "Version $selected of ${artifact.versions.size} kept; choose a version" }) {
                    Text("v$selected")
                    AppIcon(Glyph.CHEVRON, modifier = Modifier.size(16.dp))
                }
                DropdownMenu(expanded = versionsOpen, onDismissRequest = { versionsOpen = false }) {
                    artifact.versions.asReversed().forEach { item ->
                        DropdownMenuItem(text = { Text(versionLine(artifact, item.number)) }, modifier = Modifier.testTag("version-${item.number}"),
                            trailingIcon = { if (item.number == selected) AppIcon(Glyph.CHECK, "Selected") },
                            onClick = {
                                versionsOpen = false
                                selected = item.number
                                editor.reset(item.source)
                                saveDraft(artifact.id, null)
                                renderKey++
                            })
                    }
                }
            }
            Box {
                IconButton(onClick = { moreOpen = true }) { AppIcon(Glyph.MORE, "More artifact actions") }
                DropdownMenu(expanded = moreOpen, onDismissRequest = { moreOpen = false }) {
                    DropdownMenuItem(text = { Text("Save as…") }, onClick = { moreOpen = false; onSaveAs(fileName, editor.text) })
                    DropdownMenuItem(text = { Text("Share") }, onClick = { moreOpen = false; onShare(fileName, editor.text) })
                }
            }
        }
        Text(versionLine(artifact, selected), Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("artifact-diff"),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TabRow(selectedTabIndex = if (sourceTab) 1 else 0, containerColor = MaterialTheme.colorScheme.surface) {
            Tab(selected = !sourceTab, onClick = { sourceTab = false }, text = { Text("Preview") }, modifier = Modifier.testTag("tab-preview"))
            Tab(selected = sourceTab, onClick = { sourceTab = true }, text = { Text("Source") }, modifier = Modifier.testTag("tab-source"))
        }
        if (pane.revising) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text("DeepSeek is writing v${artifact.latest.number + 1}… The reply also appears in the chat.",
                Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall)
        }
        if (sourceTab && editor.findOpen) FindBar(editor) { editor.findOpen = false }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (sourceTab) CodeEditor(editor, textScale, Modifier.fillMaxSize())
            else when (artifact.kind) {
                ArtifactKind.HTML -> HtmlPreview(version.source, network, renderKey)
                ArtifactKind.SVG -> SvgPreview(version.source, renderKey, artifact.title)
                ArtifactKind.MARKDOWN -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).testTag("markdown-preview")) {
                    key(renderKey) { MarkdownText(version.source) }
                }
            }
        }
        if (dirty) Text("Edited. Run makes v${artifact.latest.number + 1}.", Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        FlowRow(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (sourceTab) {
                TextButton(onClick = editor::undo, enabled = editor.canUndo) { Text("Undo") }
                TextButton(onClick = editor::redo, enabled = editor.canRedo) { Text("Redo") }
                TextButton(onClick = { editor.findOpen = !editor.findOpen }) { Text("Find") }
                if (dirty) TextButton(onClick = { editor.reset(version.source); saveDraft(artifact.id, null) }) { Text("Revert") }
            } else if (artifact.kind == ArtifactKind.HTML) {
                TextButton(onClick = { if (network) { network = false; renderKey++ } else confirmNetwork = true }, modifier = Modifier.testTag("allow-network")) {
                    Text(if (network) "Block network" else "Allow network for this preview")
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                if (dirty) onRun(editor.text)
                renderKey++
                sourceTab = false
            }, enabled = !pane.revising, modifier = Modifier.testTag("artifact-run")) { Text("Run") }
            OutlinedButton(onClick = { asking = true }, enabled = !chatBusy && !pane.revising, modifier = Modifier.weight(1f).testTag("artifact-ask")) {
                Text("Ask DeepSeek to change this", maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }

    if (asking) AlertDialog(onDismissRequest = { asking = false }, title = { Text("Ask DeepSeek to change this") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("DeepSeek gets this source${if (dirty) ", with your edits," else ""} and your request as a new message in this chat. " +
                    "The code block in its reply becomes the next version.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(instruction, { instruction = it.take(2000) }, label = { Text("What should change?") },
                    modifier = Modifier.fillMaxWidth().testTag("revision-instruction"))
            }
        },
        confirmButton = {
            TextButton(onClick = { asking = false; onAsk(selected, editor.text, instruction); instruction = "" }, enabled = instruction.isNotBlank(),
                modifier = Modifier.testTag("revision-send")) { Text("Send to DeepSeek") }
        },
        dismissButton = { TextButton(onClick = { asking = false }) { Text("Cancel") } })
    if (confirmNetwork) AlertDialog(onDismissRequest = { confirmNetwork = false }, title = { Text("Allow network for this preview?") },
        text = { Text("Until you close it or pick another version, this page may load files from public HTTPS sites, and those sites see your " +
            "IP address. It still cannot read your files, accounts or other apps.") },
        confirmButton = { TextButton(onClick = { confirmNetwork = false; network = true; renderKey++ }) { Text("Allow once") } },
        dismissButton = { TextButton(onClick = { confirmNetwork = false }) { Text("Cancel") } })
}

@Composable
private fun HtmlPreview(source: String, network: Boolean, renderKey: Int) {
    var failed by remember(source, network, renderKey) { mutableStateOf(false) }
    if (failed) {
        Text("The preview stopped. Tap Run to load it again.", Modifier.padding(24.dp), color = MaterialTheme.colorScheme.error)
        return
    }
    key(source, network, renderKey) {
        val holder = remember { arrayOfNulls<WebView>(1) }
        DisposableEffect(Unit) { onDispose { holder[0]?.apply { stopLoading(); destroy() }; holder[0] = null } }
        AndroidView(modifier = Modifier.fillMaxSize().testTag("html-preview"), factory = { context ->
            SandboxWebViews.create(context, javaScriptEnabled = true, approvedNetwork = network, onFailure = { failed = true }).also {
                holder[0] = it
                SandboxWebViews.loadPreview(it, source, javaScriptEnabled = true, approvedNetwork = network)
            }
        })
    }
}

@Composable
private fun SvgPreview(source: String, renderKey: Int, title: String) {
    var bitmap by remember(source, renderKey) { mutableStateOf<Bitmap?>(null) }
    var failed by remember(source, renderKey) { mutableStateOf(false) }
    LaunchedEffect(source, renderKey) {
        try { bitmap = withContext(Dispatchers.Default) { AndroidSvgRasterizer.bitmap(source) } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { failed = true }
    }
    val image = bitmap
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        when {
            failed -> Text("This SVG could not be drawn. Check the source and tap Run.", Modifier.padding(24.dp), color = MaterialTheme.colorScheme.error)
            image == null -> CircularProgressIndicator()
            else -> Image(image.asImageBitmap(), "Preview of $title", Modifier.fillMaxSize().padding(16.dp).testTag("svg-preview"), contentScale = ContentScale.Fit)
        }
    }
}
