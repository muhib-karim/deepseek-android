package app.dsandroid.ui

import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.dsandroid.R
import app.dsandroid.core.artifacts.WebProject
import app.dsandroid.core.artifacts.ArtifactStore
import app.dsandroid.core.artifacts.CsvPreview
import app.dsandroid.core.artifacts.MarkdownPreview
import app.dsandroid.core.artifacts.MermaidPreview
import app.dsandroid.core.guard.WorkspacePath
import app.dsandroid.core.tools.Artifact
import app.dsandroid.core.tools.SvgSanitizer
import app.dsandroid.sandbox.SandboxWebViews
import kotlinx.coroutines.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ArtifactCanvas(original: Artifact, workspace: WorkspacePath, back: () -> Unit,
    share: (Artifact) -> Unit, save: (Artifact) -> Unit, exportPng: (Artifact) -> Unit) {
    val store = remember(workspace) { ArtifactStore(workspace) }
    val scope = rememberCoroutineScope()
    var selectedPath by rememberSaveable(original.path) { mutableStateOf<String?>(null) }
    var selected by remember(original.path) { mutableStateOf(original) }
    var versions by remember(original.path) { mutableStateOf(listOf(original)) }
    var source by remember(original.path) { mutableStateOf<String?>(null) }
    var draft by remember(original.path) { mutableStateOf("") }
    var codeTab by rememberSaveable(original.path) { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var historyOpen by remember { mutableStateOf(false) }
    var confirmNetwork by remember { mutableStateOf(false) }
    var approvedNetwork by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }
    var revision by remember { mutableIntStateOf(0) }
    val dirty = source != null && draft != source
    val html = original.mimeType == "text/html"
    val project = original.mimeType == WebProject.MIME
    fun export(selectedArtifact: Artifact, action: (Artifact) -> Unit) {
        if (!project) { action(selectedArtifact); return }
        if (busy) return
        busy = true
        scope.launch {
            try {
                val zip = withContext(Dispatchers.IO) {
                    val content = WebProject.decode(store.read(selectedArtifact))
                    val artifact = app.dsandroid.core.tools.writeArtifact(app.dsandroid.core.tools.ToolContext(workspace),
                        "project-${java.util.UUID.randomUUID()}.zip", ".zip", "application/zip", content.zip()).artifacts.single()
                    app.dsandroid.core.artifacts.ArtifactLibrary(workspace).inherit(selectedArtifact, artifact)
                    artifact
                }
                action(zip)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { failed = true }
            finally { busy = false }
        }
    }
    fun leave() { if (dirty) confirmDiscard = true else back() }
    BackHandler { if (!busy) leave() }

    LaunchedEffect(original.path) {
        busy = true
        try {
            val loaded = withContext(Dispatchers.IO) { store.history(original) }
            versions = loaded
            selected = loaded.firstOrNull { it.path == selectedPath } ?: loaded.first()
            selectedPath = selected.path
            source = withContext(Dispatchers.IO) { store.read(selected) }
            draft = source.orEmpty()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { failed = true }
        finally { busy = false }
    }
    fun choose(artifact: Artifact) {
        if (busy || dirty) return
        busy = true; historyOpen = false; approvedNetwork = false; failed = false; source = null
        scope.launch {
            try {
                val content = withContext(Dispatchers.IO) { store.read(artifact) }
                selected = artifact; selectedPath = artifact.path; source = content; draft = content
                revision++
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { failed = true }
            finally { busy = false }
        }
    }
    fun applyAndRun() {
        if (busy || source == null) return
        busy = true; failed = false; approvedNetwork = false
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO + NonCancellable) {
                    val updated = if (dirty) store.edit(selected, draft) else selected
                    app.dsandroid.core.artifacts.ArtifactLibrary(workspace).inherit(selected, updated)
                    Triple(updated, store.history(updated), store.read(updated))
                }
                selected = result.first; selectedPath = selected.path; versions = result.second
                source = result.third; draft = result.third; revision++; codeTab = false
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { failed = true }
            finally { busy = false }
        }
    }
    Scaffold(topBar = {
        TopAppBar(title = { Text(original.fileName, maxLines = 1) }, navigationIcon = {
            TextButton(onClick = ::leave, enabled = !busy) { Text(stringResource(R.string.viewer_back)) }
        })
    }, bottomBar = {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { export(selected, share) }, enabled = source != null && !busy && !dirty, modifier = Modifier.weight(1f)) { Text(stringResource(if (project) R.string.project_share_zip else R.string.viewer_share)) }
                Button(onClick = { export(selected, save) }, enabled = source != null && !busy && !dirty, modifier = Modifier.weight(1f)) { Text(stringResource(if (project) R.string.project_save_zip else R.string.viewer_save)) }
            }
            if (original.mimeType == "image/svg+xml") TextButton(onClick = { exportPng(selected) }, enabled = source != null && !busy && !dirty) {
                Text(stringResource(R.string.viewer_export_png))
            }
        }
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            TabRow(selectedTabIndex = if (codeTab) 1 else 0) {
                Tab(selected = !codeTab, onClick = { codeTab = false }, text = { Text(stringResource(R.string.canvas_preview)) })
                Tab(selected = codeTab, onClick = { codeTab = true }, text = { Text(stringResource(R.string.interpreter_code)) })
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Box {
                    TextButton(onClick = { historyOpen = true }, enabled = !busy && !dirty) { Text(stringResource(R.string.canvas_history, versions.size)) }
                    DropdownMenu(expanded = historyOpen, onDismissRequest = { historyOpen = false }) {
                        versions.forEachIndexed { index, artifact ->
                            DropdownMenuItem(text = { Text(stringResource(R.string.canvas_version, versions.size - index)) },
                                onClick = { choose(artifact) }, trailingIcon = { if (artifact == selected) AppIcon(Glyph.CHECK) })
                        }
                    }
                }
                TextButton(onClick = ::applyAndRun, enabled = !busy && source != null) {
                    Text(stringResource(if (dirty) R.string.canvas_apply_run else R.string.canvas_rerun))
                }
            }
            if (html && !codeTab) TextButton(onClick = {
                if (approvedNetwork) { approvedNetwork = false; revision++ } else confirmNetwork = true
            }, enabled = source != null && !busy) {
                Text(stringResource(if (approvedNetwork) R.string.canvas_block_network else R.string.canvas_allow_network))
            }
            if (dirty) Text(stringResource(R.string.canvas_unsaved), Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall)
            if (failed) Text(stringResource(R.string.canvas_operation_failed), Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
            Box(Modifier.fillMaxWidth().weight(1f)) {
                val content = source
                when {
                    busy -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                    content == null -> Unit
                    codeTab && project -> WebProjectEditor(draft) { draft = it }
                    codeTab -> OutlinedTextField(value = draft, onValueChange = { value ->
                        if (value.length <= ArtifactStore.MAX_BYTES) draft = value
                    }, modifier = Modifier.fillMaxSize().padding(12.dp), label = { Text(stringResource(R.string.canvas_source)) },
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
                    else -> key(selected.path, revision, approvedNetwork) {
                        CanvasPreview(content, selected.mimeType, approvedNetwork)
                    }
                }
            }
        }
    }
    if (confirmNetwork) AlertDialog(onDismissRequest = { confirmNetwork = false },
        title = { Text(stringResource(R.string.canvas_allow_network)) },
        text = { Text(stringResource(R.string.canvas_network_notice)) },
        confirmButton = { TextButton(onClick = { confirmNetwork = false; approvedNetwork = true; revision++ }) { Text(stringResource(R.string.allow_once)) } },
        dismissButton = { TextButton(onClick = { confirmNetwork = false }) { Text(stringResource(R.string.deny)) } })
    if (confirmDiscard) AlertDialog(onDismissRequest = { confirmDiscard = false },
        title = { Text(stringResource(R.string.canvas_discard_title)) },
        text = { Text(stringResource(R.string.canvas_discard_notice)) },
        confirmButton = { TextButton(onClick = { confirmDiscard = false; back() }) { Text(stringResource(R.string.canvas_discard)) } },
        dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text(stringResource(R.string.cancel)) } })
}

@Composable
private fun CanvasPreview(source: String, mime: String, approvedNetwork: Boolean) {
    var document by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    var project by remember { mutableStateOf<WebProject?>(null) }
    LaunchedEffect(source, mime) {
        try {
            project = if (mime == WebProject.MIME) withContext(Dispatchers.Default) { WebProject.decode(source) } else null
            document = withContext(Dispatchers.Default) {
                when (mime) {
                    WebProject.MIME -> requireNotNull(project).preview()
                    "text/html" -> source
                    "image/svg+xml" -> SvgSanitizer.sanitize(source)
                    "text/markdown" -> MarkdownPreview.render(source)
                    "text/vnd.mermaid" -> MermaidPreview.render(source)
                    "text/csv" -> CsvPreview.render(source)
                    else -> "<pre>" + source.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;") + "</pre>"
                }.also { require(it == null || it.length <= ArtifactStore.MAX_BYTES) }
            }
            failed = document == null
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { failed = true }
    }
    when {
        failed -> Text(stringResource(when (mime) {
            "text/vnd.mermaid" -> R.string.canvas_mermaid_limit
            "text/csv" -> R.string.canvas_csv_limit
            else -> R.string.viewer_error
        }),
            Modifier.padding(24.dp).verticalScroll(rememberScrollState()))
        document == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        else -> {
            val content = requireNotNull(document)
            val holder = remember { arrayOfNulls<WebView>(1) }
            DisposableEffect(Unit) {
                onDispose { holder[0]?.apply { stopLoading(); destroy() }; holder[0] = null }
            }
            AndroidView(modifier = Modifier.fillMaxSize(), factory = { context ->
                val local = project
                SandboxWebViews.create(context, mime == "text/html" || local != null, approvedNetwork,
                    cachedResource = if (local == null) null else { url ->
                        local.resource(url)?.let { (type, text) -> android.webkit.WebResourceResponse(type, "UTF-8", text.byteInputStream()) }
                    }, onFailure = { failed = true }).also {
                    holder[0] = it
                    SandboxWebViews.loadPreview(it, content, mime == "text/html" || local != null, approvedNetwork, localProject = local != null)
                }
            })
        }
    }
}
