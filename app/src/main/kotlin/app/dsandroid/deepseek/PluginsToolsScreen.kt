package app.dsandroid.deepseek

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.dsandroid.core.mcp.RemoteTool
import app.dsandroid.tools.ToolCatalog
import app.dsandroid.tools.ToolRoute
import app.dsandroid.ui.LocalOfficialColors

/**
 * Plugins & tools: a switch for every tool agent mode may use in DeepSeek chats (app.dsandroid.tools.ToolCatalog), for
 * each enabled skill's functions and for each remote MCP server's tools. A switched-off tool is not offered to DeepSeek
 * and is refused if asked for. The switches are shared with the API-key client, which also has tools listed at the end.
 */
@Composable
internal fun PluginsToolsPage(state: AccountUiState, actions: AgentActions, onNavigate: (AccountPage) -> Unit, modifier: Modifier) {
    var adding by remember { mutableStateOf(false) }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val enabled = !state.busy && !state.toolsetBusy
    Column(modifier.fillMaxWidth().background(LocalOfficialColors.current.groupedBackground).verticalScroll(rememberScrollState())
        .padding(horizontal = 16.dp, vertical = 8.dp).testTag("plugins-page"), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Agent mode can use these tools in your DeepSeek chats. A switched-off tool is not offered to DeepSeek and is refused if " +
            "DeepSeek asks for it. Tools that reach the network or other apps ask before each use. The switches also apply to the API-key client.",
            Modifier.padding(horizontal = 4.dp), style = MaterialTheme.typography.bodySmall, color = muted)
        SettingsGroup("Trusted execution") {
            ToolSwitchRow("Native Python and skill code", "Lets the agent run Python, Python packages and skill functions on this phone with this " +
                "app's rights. This is not a sandbox: switch it on only for code you trust. The code sandbox below needs no such permission.",
                "Each run asks first", state.trustedExecution, "trusted-execution", enabled) { actions.setTrustedExecution(it) }
        }
        ToolCatalog.forRoute(ToolRoute.ACCOUNT).groupBy { it.group }.forEach { (group, tools) ->
            SettingsGroup(group.label) {
                tools.forEach { tool ->
                    val allowed = !tool.trusted || state.trustedExecution
                    ToolSwitchRow(tool.label, tool.summary, if (allowed) tool.approval else NEEDS_TRUSTED,
                        allowed && ToolCatalog.isEnabled(tool.name, state.disabledTools), "tool-${tool.name}", enabled && allowed) {
                        actions.setToolEnabled(tool.name, it)
                    }
                }
            }
        }
        SettingsGroup("Skill functions") {
            val functions = state.skills.filter { it.enabled }.flatMap { skill -> skill.functions.map { it to skill.name } }
            if (functions.isEmpty()) Text("No enabled skill has functions.", Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall, color = muted)
            functions.forEach { (function, skill) ->
                ToolSwitchRow(function.substringAfter("__"), "From the $skill skill; runs as Python on this phone with this app's rights.",
                    if (state.trustedExecution) "Asks before each run" else NEEDS_TRUSTED,
                    state.trustedExecution && ToolCatalog.isEnabled(function, state.disabledTools), "tool-$function", enabled && state.trustedExecution) {
                    actions.setToolEnabled(function, it)
                }
            }
            SettingsRow("Manage skills", tag = "plugins-skills") { onNavigate(AccountPage.SKILLS) }
        }
        SettingsGroup("Remote tools (MCP)") {
            if (state.remoteServers.isEmpty()) Text("Add an HTTPS MCP server to use its tools here. Each call asks first, and the tool's answer is sent to DeepSeek.",
                Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall, color = muted)
            state.remoteServers.forEach { server ->
                Column(Modifier.fillMaxWidth().padding(top = 8.dp).testTag("server-${server.id}")) {
                    Text(server.name, Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                    Text(server.url, Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (server.tools.isEmpty()) Text("This server lists no tools.", Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall, color = muted)
                    server.tools.forEach { tool ->
                        val id = RemoteTool.registryName(server.id, tool.name)
                        ToolSwitchRow(tool.name, tool.description.ifBlank { "Remote tool" }.take(160), "Asks before each call",
                            ToolCatalog.isEnabled(id, state.disabledTools), "tool-$id", enabled) { actions.setToolEnabled(id, it) }
                    }
                    TextButton(onClick = { actions.removeRemoteServer(server.id) }, enabled = enabled, modifier = Modifier.padding(horizontal = 4.dp)) {
                        Text("Remove ${server.name}", color = LocalOfficialColors.current.destructive)
                    }
                }
            }
            SettingsRow("Add MCP server", tag = "plugins-add-server", enabled = enabled && state.remoteServers.size < 8) { adding = true }
        }
        SettingsGroup("In the API-key client") {
            Text(ToolCatalog.entries.filter { ToolRoute.ACCOUNT !in it.routes }.joinToString(", ") { it.label },
                Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp), style = MaterialTheme.typography.bodyMedium)
            Text("These need that client's screens or its function calling, so DeepSeek chats here do not offer them.",
                Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall, color = muted)
        }
    }
    if (adding) {
        var name by remember { mutableStateOf("") }
        var url by remember { mutableStateOf("https://") }
        AlertDialog(onDismissRequest = { adding = false }, title = { Text("Add MCP server") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("The app reads the server's tool list now. Later, each call asks first and sends the tool's answer to DeepSeek.",
                        style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(name, { name = it.take(100) }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("server-name"))
                    OutlinedTextField(url, { url = it.take(2048) }, label = { Text("HTTPS address") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("server-url"))
                }
            },
            confirmButton = {
                TextButton(onClick = { adding = false; actions.addRemoteServer(name, url) }, enabled = name.isNotBlank() && url.trim().length > 8 && url.trim().startsWith("https://"),
                    modifier = Modifier.testTag("server-connect")) { Text("Connect") }
            },
            dismissButton = { TextButton(onClick = { adding = false }) { Text("Cancel") } })
    }
}

private const val NEEDS_TRUSTED = "Needs trusted execution (above)"

@Composable
private fun ToolSwitchRow(label: String, summary: String, approval: String?, checked: Boolean, tag: String, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onChange).heightIn(min = 56.dp)
        .padding(horizontal = 16.dp, vertical = 8.dp).testTag(tag), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            approval?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary) }
        }
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}
