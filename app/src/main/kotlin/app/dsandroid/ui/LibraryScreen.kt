package app.dsandroid.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.dsandroid.R
import app.dsandroid.core.artifacts.*
import app.dsandroid.core.tools.Artifact

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(entries: List<LibraryEntry>, busy: Boolean, back: () -> Unit, open: (Artifact) -> Unit,
    share: (Artifact) -> Unit, delete: (Artifact) -> Unit, sourceChat: (String) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var type by rememberSaveable { mutableStateOf(LibraryType.ALL.name) }
    var deleting by remember { mutableStateOf<Artifact?>(null) }
    val filtered = remember(entries, query, type) { ArtifactLibrary.filter(entries, query, LibraryType.valueOf(type)) }
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.library)) }, navigationIcon = {
        TextButton(onClick = back) { Text(stringResource(R.string.back)) }
    }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
            OutlinedTextField(query, { query = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                label = { Text(stringResource(R.string.library_search)) })
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LibraryType.entries.forEach { value ->
                    FilterChip(selected = type == value.name, onClick = { type = value.name }, label = { Text(stringResource(when (value) {
                        LibraryType.ALL -> R.string.library_all
                        LibraryType.DOCUMENT -> R.string.library_documents
                        LibraryType.IMAGE -> R.string.library_images
                        LibraryType.ARTIFACT -> R.string.library_artifacts
                        LibraryType.ARCHIVE -> R.string.library_archives
                        LibraryType.OTHER -> R.string.library_other
                    })) })
                }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (!busy && filtered.isEmpty()) Text(stringResource(R.string.library_empty), Modifier.padding(vertical = 24.dp))
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(filtered, key = { it.artifact.path }) { entry ->
                    OutlinedCard(onClick = { open(entry.artifact) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Text(entry.artifact.fileName, style = MaterialTheme.typography.titleMedium)
                            Text(stringResource(R.string.library_file_size, (entry.bytes + 1023) / 1024), style = MaterialTheme.typography.bodySmall)
                            entry.chatTitle?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                            Row(Modifier.horizontalScroll(rememberScrollState())) {
                                TextButton(onClick = { open(entry.artifact) }, enabled = !busy) { Text(stringResource(R.string.library_open)) }
                                TextButton(onClick = { share(entry.artifact) }, enabled = !busy) { Text(stringResource(R.string.viewer_share)) }
                                entry.conversationId?.let { id ->
                                    TextButton(onClick = { sourceChat(id) }, enabled = !busy) { Text(stringResource(R.string.library_source)) }
                                }
                                TextButton(onClick = { deleting = entry.artifact }, enabled = !busy) { Text(stringResource(R.string.delete)) }
                            }
                        }
                    }
                }
            }
        }
    }
    deleting?.let { artifact ->
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text(stringResource(R.string.delete)) },
            text = { Text(stringResource(R.string.library_delete_notice, artifact.fileName)) },
            confirmButton = { TextButton(onClick = { deleting = null; delete(artifact) }) { Text(stringResource(R.string.delete)) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.cancel)) } })
    }
}
