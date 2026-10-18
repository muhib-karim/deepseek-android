package app.dsandroid.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.dsandroid.R
import app.dsandroid.core.mcp.*
import app.dsandroid.core.personalization.SavedFact
import app.dsandroid.data.AppSettings
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun MemorySettings(facts: List<SavedFact>, enabled: Boolean, busy: Boolean,
    toggle: (Boolean) -> Unit, save: (String?, String) -> Unit, delete: (String) -> Unit) {
    var editing by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<SavedFact?>(null) }
    var draft by remember { mutableStateOf("") }
    var deleting by remember { mutableStateOf<SavedFact?>(null) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.memory_enabled), Modifier.weight(1f))
        Switch(enabled, toggle, enabled = !busy)
    }
    Text(stringResource(R.string.memory_notice), style = MaterialTheme.typography.bodySmall)
    if (facts.isEmpty()) Text(stringResource(R.string.memory_empty))
    facts.forEach { fact ->
        OutlinedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text(fact.text)
                Row {
                    TextButton(onClick = { selected = fact; draft = fact.text; editing = true }, enabled = !busy) { Text(stringResource(R.string.turn_edit)) }
                    TextButton(onClick = { deleting = fact }, enabled = !busy) { Text(stringResource(R.string.delete)) }
                }
            }
        }
    }
    OutlinedButton(onClick = { selected = null; draft = ""; editing = true }, enabled = !busy && facts.size < 100) { Text(stringResource(R.string.memory_add)) }
    if (editing) AlertDialog(onDismissRequest = { editing = false }, title = { Text(stringResource(R.string.memory_fact)) },
        text = { OutlinedTextField(draft, { draft = it.take(2000) }, label = { Text(stringResource(R.string.memory_fact)) }, maxLines = 8) },
        confirmButton = { TextButton(onClick = { save(selected?.id, draft); editing = false }, enabled = draft.isNotBlank() && !busy) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = { editing = false }) { Text(stringResource(R.string.cancel)) } })
    deleting?.let { fact -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text(stringResource(R.string.delete)) },
        text = { Text(stringResource(R.string.memory_delete_notice)) },
        confirmButton = { TextButton(onClick = { delete(fact.id); deleting = null }, enabled = !busy) { Text(stringResource(R.string.delete)) } },
        dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.cancel)) } }) }
}

@Composable
fun ToolsSettings(draft: AppSettings, builtIns: List<String>, busy: Boolean, update: (AppSettings) -> Unit) {
    var adding by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var endpoint by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun toggle(id: String, on: Boolean) {
        update(draft.copy(disabledTools = if (on) draft.disabledTools - id else draft.disabledTools + id))
    }
    Text(stringResource(R.string.tools_notice), style = MaterialTheme.typography.bodySmall)
    Text(stringResource(R.string.tools_builtin), style = MaterialTheme.typography.titleMedium)
    builtIns.forEach { id -> ToolSwitch(id, id !in draft.disabledTools, !busy) { toggle(id, it) } }
    Text(stringResource(R.string.tools_custom), style = MaterialTheme.typography.titleMedium)
    if (draft.remoteServers.isEmpty()) Text(stringResource(R.string.tools_empty))
    draft.remoteServers.forEach { server ->
        OutlinedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(server.name, style = MaterialTheme.typography.titleSmall)
                Text(server.url, style = MaterialTheme.typography.bodySmall)
                if (server.tools.isEmpty()) Text(stringResource(R.string.tools_server_empty))
                server.tools.forEach { tool ->
                    val id = RemoteTool.registryName(server.id, tool.name)
                    ToolSwitch(tool.name, id !in draft.disabledTools, !busy) { toggle(id, it) }
                }
                TextButton(onClick = {
                    val ids = server.tools.map { RemoteTool.registryName(server.id, it.name) }.toSet()
                    update(draft.copy(remoteServers = draft.remoteServers.filterNot { it.id == server.id }, disabledTools = draft.disabledTools - ids))
                }, enabled = !busy) { Text(stringResource(R.string.tools_remove_server)) }
            }
        }
    }
    OutlinedButton(onClick = { name = ""; endpoint = ""; failed = false; adding = true }, enabled = !busy && draft.remoteServers.size < 8) { Text(stringResource(R.string.tools_add_server)) }
    if (adding) AlertDialog(onDismissRequest = { if (!loading) adding = false }, title = { Text(stringResource(R.string.tools_add_server)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.tools_connect_notice))
                OutlinedTextField(name, { name = it.take(100) }, label = { Text(stringResource(R.string.tools_server_name)) }, enabled = !loading, singleLine = true)
                OutlinedTextField(endpoint, { endpoint = it.take(2048) }, label = { Text(stringResource(R.string.tools_server_url)) }, enabled = !loading, singleLine = true)
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (failed) Text(stringResource(R.string.tools_connect_error), color = MaterialTheme.colorScheme.error)
            }
        }, confirmButton = { TextButton(onClick = {
            loading = true; failed = false
            scope.launch {
                try {
                    val url = McpEndpoint.validate(endpoint.trim())
                    val tools = McpClient(url).listTools()
                    update(draft.copy(remoteServers = draft.remoteServers + RemoteServer(UUID.randomUUID().toString(), name.trim(), url, tools)))
                    adding = false
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { failed = true }
                finally { loading = false }
            }
        }, enabled = !loading && name.isNotBlank() && endpoint.isNotBlank()) { Text(stringResource(R.string.tools_connect)) } },
        dismissButton = { TextButton(onClick = { adding = false }, enabled = !loading) { Text(stringResource(R.string.cancel)) } })
}

@Composable
private fun ToolSwitch(label: String, checked: Boolean, enabled: Boolean, update: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked, update, enabled = enabled)
    }
}

@Composable
fun InstructionsDialog(initial: String, save: (String) -> Unit, dismiss: () -> Unit) {
    var draft by remember(initial) { mutableStateOf(initial) }
    AlertDialog(onDismissRequest = dismiss, title = { Text(stringResource(R.string.chat_instructions)) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.chat_instructions_notice))
            OutlinedTextField(draft, { draft = it.take(8000) }, label = { Text(stringResource(R.string.instructions)) }, maxLines = 10)
        } },
        confirmButton = { TextButton(onClick = { save(draft); dismiss() }) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.cancel)) } })
}
