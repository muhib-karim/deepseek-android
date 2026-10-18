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
    val connector = confirmation.name.startsWith("connector_") && confirmation.destination != null
    ModalBottomSheet(onDismissRequest = { reply(false) }) {
        Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.allow_tool), style = MaterialTheme.typography.titleLarge)
            confirmation.destination?.let { destination ->
                Text(stringResource(R.string.tools_destination, destination))
                Text(confirmation.remoteName.orEmpty(), style = MaterialTheme.typography.titleMedium)
            }
            val phoneLabel = phoneActionLabel(confirmation.name)
            Text(if (phoneLabel != null) stringResource(phoneLabel) else if (confirmation.name == "python_runtime_download") stringResource(R.string.python_download_title) else confirmation.name,
                style = MaterialTheme.typography.titleMedium)
            Text(stringResource(when (confirmation.name) {
                "open_url", "read_page", "click", "type", "submit", "back" -> R.string.browser_confirm
                "draft_sms", "draft_email", "add_calendar_event", "open_map" -> R.string.phone_action_confirm
                "schedule_prompt" -> R.string.task_confirm_notice
                "remember" -> R.string.confirm_memory_explanation
                "web_search" -> R.string.confirm_search_explanation
                "web_fetch", "visit_web" -> R.string.confirm_fetch_explanation
                "python_runtime_download" -> R.string.python_download_explanation
                else -> if (connector) R.string.confirm_connector_explanation else if (confirmation.destination != null) R.string.confirm_remote_explanation else R.string.confirm_tool_explanation
            }))
            if (confirmation.name == "add_calendar_event") PhoneCalendarTimes(confirmation.arguments)
            SelectionContainer {
                Text(confirmation.arguments.toString(), Modifier.heightIn(max = 320.dp)
                    .verticalScroll(rememberScrollState()), fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { reply(false) }) { Text(stringResource(R.string.deny)) }
                Button(onClick = { reply(true) }) { Text(stringResource(if (connector) R.string.allow_connector_chat else R.string.allow_once)) }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}
