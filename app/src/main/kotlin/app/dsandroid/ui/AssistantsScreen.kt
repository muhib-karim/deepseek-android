package app.dsandroid.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.dsandroid.R
import app.dsandroid.core.assistants.*
import app.dsandroid.core.tools.Artifact
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssistantsScreen(state: ChatUiState, vm: ChatViewModel, back: () -> Unit, start: (SavedAssistant) -> Unit, export: (Artifact) -> Unit) {
    var editing by remember { mutableStateOf<SavedAssistant?>(null) }
    var deleting by remember { mutableStateOf<SavedAssistant?>(null) }
    val settings by vm.settings.collectAsState()
    val toolNames = remember(settings.remoteServers, settings.connectors) { vm.assistantToolNames() }
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.assistants)) }, navigationIcon = {
        TextButton(onClick = back) { Text(stringResource(R.string.back)) }
    }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.assistants_notice))
            Button(onClick = { editing = SavedAssistant(UUID.randomUUID().toString(), "") }, enabled = !state.personalizing && state.assistants.size < 50) { Text(stringResource(R.string.assistant_create)) }
            state.assistants.forEach { assistant ->
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text("${assistant.icon.symbol()} ${assistant.name}", style = MaterialTheme.typography.titleMedium)
                        Text(assistant.instructions, maxLines = 3)
                        Row {
                            TextButton(onClick = { start(assistant) }, enabled = !state.running && !state.importing) { Text(stringResource(R.string.assistant_start)) }
                            TextButton(onClick = { editing = assistant }, enabled = !state.personalizing) { Text(stringResource(R.string.turn_edit)) }
                        }
                        Row {
                            TextButton(onClick = { vm.exportAssistant(assistant, export) }, enabled = !state.personalizing) { Text(stringResource(R.string.assistant_export)) }
                            TextButton(onClick = { deleting = assistant }, enabled = !state.personalizing) { Text(stringResource(R.string.delete)) }
                        }
                    }
                }
            }
        }
    }
    editing?.let { original ->
        var draft by remember(original.id) { mutableStateOf(original) }
        AlertDialog(onDismissRequest = { editing = null }, title = { Text(stringResource(R.string.assistants)) }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(draft.name, { draft = draft.copy(name = it.take(80)) }, label = { Text(stringResource(R.string.assistant_name)) })
                Text(stringResource(R.string.assistant_icon))
                Row { AssistantIcon.entries.forEach { icon -> FilterChip(selected = draft.icon == icon, onClick = { draft = draft.copy(icon = icon) }, label = { Text(icon.symbol()) }) } }
                OutlinedTextField(draft.instructions, { draft = draft.copy(instructions = it.take(8000)) }, label = { Text(stringResource(R.string.instructions)) }, maxLines = 6)
                Text(stringResource(R.string.response_style_value, stringResource(styleLabel(draft.responseStyle))))
                app.dsandroid.core.personalization.ResponseStyle.entries.forEach { style ->
                    FilterChip(selected = draft.responseStyle == style, onClick = { draft = draft.copy(responseStyle = style) }, label = { Text(stringResource(styleLabel(style))) })
                }
                Text(stringResource(R.string.assistant_tools))
                toolNames.forEach { name ->
                    Row { Checkbox(name in draft.defaultTools, { checked -> draft = draft.copy(defaultTools = if (checked) draft.defaultTools + name else draft.defaultTools - name) }); Text(name) }
                }
            }
        }, confirmButton = { TextButton(onClick = { vm.saveAssistant(draft); editing = null }, enabled = draft.name.isNotBlank() && !state.personalizing) { Text(stringResource(R.string.save)) } },
            dismissButton = { TextButton(onClick = { editing = null }) { Text(stringResource(R.string.cancel)) } })
    }
    deleting?.let { assistant -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text(stringResource(R.string.delete)) },
        text = { Text(stringResource(R.string.assistant_delete_notice, assistant.name)) },
        confirmButton = { TextButton(onClick = { vm.deleteAssistant(assistant.id); deleting = null }) { Text(stringResource(R.string.delete)) } },
        dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.cancel)) } }) }
}

private fun AssistantIcon.symbol() = when (this) { AssistantIcon.SPARK -> "✦"; AssistantIcon.BOOK -> "▤"; AssistantIcon.CODE -> "</>"; AssistantIcon.PEN -> "✎" }
