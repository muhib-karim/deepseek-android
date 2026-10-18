package app.dsandroid.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.dsandroid.R
import app.dsandroid.core.artifacts.WebProject

@Composable
internal fun WebProjectEditor(source: String, change: (String) -> Unit) {
    val project = remember(source) { runCatching { WebProject.decode(source) }.getOrNull() }
    var path by rememberSaveable { mutableStateOf("index.html") }
    var filesOpen by remember { mutableStateOf(false) }
    var limit by remember { mutableStateOf(false) }
    if (project == null) { Text(stringResource(R.string.viewer_error)); return }
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Box {
            TextButton(onClick = { filesOpen = true }) { Text(stringResource(R.string.project_file, path)) }
            DropdownMenu(filesOpen, { filesOpen = false }) {
                project.files.keys.forEach { name ->
                    DropdownMenuItem(text = { Text(name) }, onClick = { path = name; filesOpen = false })
                }
            }
        }
        if (limit) Text(stringResource(R.string.project_file_limit), color = MaterialTheme.colorScheme.error)
        OutlinedTextField(value = project.files[path].orEmpty(), onValueChange = { content ->
            val encoded = runCatching { project.copy(files = project.files + (path to content)).encode() }.getOrNull()
            limit = encoded == null
            if (encoded != null) change(encoded)
        }, label = { Text(stringResource(R.string.canvas_source)) }, modifier = Modifier.fillMaxSize(),
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
    }
}
