package app.dsandroid.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.dsandroid.R
import app.dsandroid.core.export.ExportFormat

@Composable
fun ConversationExportDialog(report: Boolean, export: (ExportFormat) -> Unit, dismiss: () -> Unit) {
    AlertDialog(onDismissRequest = dismiss,
        title = { Text(stringResource(if (report) R.string.export_answer else R.string.export_conversation)) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.export_notice))
            ExportFormat.entries.forEach { format ->
                OutlinedButton(onClick = { export(format); dismiss() }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(when (format) {
                        ExportFormat.MARKDOWN -> R.string.export_markdown
                        ExportFormat.HTML -> R.string.export_html
                        ExportFormat.PDF -> R.string.export_pdf
                    }))
                }
            }
        } }, confirmButton = {},
        dismissButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.cancel)) } })
}
