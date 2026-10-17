package app.dsandroid.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.dsandroid.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolConfirmationSheet(confirmation: Confirmation, reply: (Boolean) -> Unit) {
    ModalBottomSheet(onDismissRequest = { reply(false) }) {
        Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.allow_tool), style = MaterialTheme.typography.titleLarge)
            Text(confirmation.name, style = MaterialTheme.typography.titleMedium)
            Text(stringResource(when (confirmation.name) {
                "web_search" -> R.string.confirm_search_explanation
                "web_fetch", "visit_web" -> R.string.confirm_fetch_explanation
                else -> R.string.confirm_tool_explanation
            }))
            SelectionContainer {
                Text(confirmation.arguments.toString(), Modifier.heightIn(max = 320.dp)
                    .verticalScroll(rememberScrollState()), fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { reply(false) }) { Text(stringResource(R.string.deny)) }
                Button(onClick = { reply(true) }) { Text(stringResource(R.string.allow_once)) }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}
