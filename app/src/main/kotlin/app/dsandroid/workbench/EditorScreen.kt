package app.dsandroid.workbench

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.dsandroid.core.editor.EditorLanguage

/**
 * The in-app editor for a workspace file or a code block: line numbers, highlighting, undo/redo, find,
 * Save (into this chat's folder), Save as (system file picker), Share and, for Python, Run on the device.
 */
@Composable
fun EditorScreen(
    file: EditorFile, textScale: Float, output: String?, running: Boolean,
    draft: (String) -> String?, saveDraft: (String, String?) -> Unit,
    onClose: () -> Unit, onSave: (String) -> Unit, onSaveAs: (String, String) -> Unit, onShare: (String, String) -> Unit,
    onRun: ((String) -> Unit)?, modifier: Modifier = Modifier,
) {
    val editor = remember(file.key) { EditorState(draft(file.key) ?: file.text, file.language) { saveDraft(file.key, it) } }
    val dirty = editor.text != file.text || file.path == null
    var confirmClose by remember { mutableStateOf(false) }
    fun close() { if (editor.text != file.text) confirmClose = true else onClose() }
    BackHandler { close() }
    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).statusBarsPadding().navigationBarsPadding().imePadding()
        .testTag("code-editor-screen")) {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = ::close) { Text("Close") }
            Column(Modifier.weight(1f).padding(horizontal = 4.dp)) {
                Text(file.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(file.language.label + " · " + when {
                    file.path == null -> "not saved yet"
                    editor.text != file.text -> "unsaved changes"
                    else -> "saved in this chat"
                }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Button(onClick = { onSave(editor.text) }, enabled = dirty, modifier = Modifier.testTag("editor-save")) { Text("Save") }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = editor::undo, enabled = editor.canUndo) { Text("Undo") }
            TextButton(onClick = editor::redo, enabled = editor.canRedo) { Text("Redo") }
            TextButton(onClick = { editor.findOpen = !editor.findOpen }, modifier = Modifier.testTag("editor-find-toggle")) { Text("Find") }
            TextButton(onClick = { onSaveAs(file.name, editor.text) }, modifier = Modifier.testTag("editor-save-as")) { Text("Save as…") }
            TextButton(onClick = { onShare(file.name, editor.text) }) { Text("Share") }
            if (onRun != null) TextButton(onClick = { onRun(editor.text) }, enabled = !running, modifier = Modifier.testTag("editor-run")) { Text("Run") }
        }
        if (editor.findOpen) FindBar(editor) { editor.findOpen = false }
        CodeEditor(editor, textScale, Modifier.weight(1f).fillMaxWidth())
        if (running || output != null) {
            HorizontalDivider()
            Column(Modifier.fillMaxWidth().heightIn(max = 220.dp).padding(12.dp).testTag("editor-output")) {
                Text(if (running) "Running on this phone…" else "Output", style = MaterialTheme.typography.labelMedium)
                if (running) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 4.dp))
                output?.let {
                    SelectionContainer {
                        Text(it, Modifier.verticalScroll(rememberScrollState()), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
    if (confirmClose) AlertDialog(onDismissRequest = { confirmClose = false }, title = { Text("Close without saving?") },
        text = { Text("Your changes stay in this editor until the app closes; open the file again to continue.") },
        confirmButton = { TextButton(onClick = { confirmClose = false; onClose() }) { Text("Close") } },
        dismissButton = { TextButton(onClick = { confirmClose = false }) { Text("Keep editing") } })
}

/** Python files get a Run button that uses the on-device run_python worker. */
fun EditorFile.runnable(): Boolean = language == EditorLanguage.PYTHON
