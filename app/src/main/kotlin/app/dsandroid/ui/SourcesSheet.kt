package app.dsandroid.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.dsandroid.R
import app.dsandroid.core.tools.WebSource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourcesSheet(sources: List<WebSource>, selected: Int?, dismiss: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    var openFailed by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = dismiss) {
        Text(stringResource(R.string.sources_title), Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
            style = MaterialTheme.typography.titleLarge)
        if (openFailed) Text(stringResource(R.string.source_open_error), Modifier.padding(horizontal = 24.dp), color = MaterialTheme.colorScheme.error)
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 560.dp), contentPadding = PaddingValues(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(sources.sortedBy { if (it.number == selected) 0 else it.number }, key = { it.number }) { source ->
                Surface(color = if (source.number == selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.medium) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(stringResource(R.string.source_number, source.number, source.title), style = MaterialTheme.typography.titleSmall)
                        Text(source.domain, style = MaterialTheme.typography.labelMedium)
                        SelectionContainer { Text(source.url, style = MaterialTheme.typography.bodySmall) }
                        TextButton(onClick = { openFailed = runCatching { uriHandler.openUri(source.url) }.isFailure }) {
                            Text(stringResource(R.string.source_open))
                        }
                    }
                }
            }
        }
    }
}
