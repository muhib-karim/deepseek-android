package app.dsandroid.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import app.dsandroid.R
import app.dsandroid.core.diagnostics.DiagnosticEntry
import java.time.Instant

@Suppress("DEPRECATION")
@Composable
fun DiagnosticsPanel(entries: List<DiagnosticEntry>, vm: ChatViewModel) {
    val clipboard = LocalClipboardManager.current
    var confirmClear by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { vm.refreshDiagnostics() }
    val text = remember(entries) { entries.asReversed().joinToString("\n") { "${Instant.ofEpochMilli(it.timestamp)} ${it.kind}: ${it.message}" } }
    Text(stringResource(R.string.diagnostics_notice))
    Button(onClick = { clipboard.setText(AnnotatedString(text)) }, enabled = entries.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.diagnostics_copy))
    }
    OutlinedButton(onClick = { confirmClear = true }, enabled = entries.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.diagnostics_clear))
    }
    if (entries.isEmpty()) Text(stringResource(R.string.diagnostics_empty))
    else SelectionContainer { Text(text, Modifier.padding(vertical = 8.dp), style = MaterialTheme.typography.bodySmall) }
    if (confirmClear) AlertDialog(onDismissRequest = { confirmClear = false },
        title = { Text(stringResource(R.string.diagnostics_clear_title)) },
        confirmButton = { TextButton(onClick = { confirmClear = false; vm.clearDiagnostics() }) { Text(stringResource(R.string.diagnostics_clear)) } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.cancel)) } })
}
