package app.dsandroid.ui

import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.dsandroid.R
import app.dsandroid.core.connectors.ConnectorConfig
import app.dsandroid.data.AppSettings
import java.util.UUID

@Composable
fun ConnectorsSettings(settings: AppSettings, state: ChatUiState, vm: ChatViewModel) {
    var editing by remember { mutableStateOf<ConnectorConfig?>(null) }
    val context = LocalContext.current
    val busy = state.running || state.saving || state.signingIn
    fun connect(config: ConnectorConfig) { vm.connect(config) { url -> CustomTabsIntent.Builder().build().launchUrl(context, Uri.parse(url)) } }
    Text(stringResource(R.string.connectors_notice))
    settings.connectors.forEach { config ->
        OutlinedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text(config.name, style = MaterialTheme.typography.titleMedium)
                Text(config.searchEndpoint, style = MaterialTheme.typography.bodySmall)
                Row {
                    TextButton(onClick = { connect(config) }, enabled = !busy) { Text(stringResource(R.string.connector_reconnect)) }
                    TextButton(onClick = { vm.disconnectConnector(config.id) }, enabled = !busy) { Text(stringResource(R.string.connector_disconnect)) }
                }
            }
        }
    }
    if (state.signingIn) TextButton(onClick = vm::cancelSignIn) { Text(stringResource(R.string.cancel)) }
    Button(onClick = { editing = ConnectorConfig(UUID.randomUUID().toString(), "", "", "", "q", "", "", "", "") }, enabled = !busy && settings.connectors.size < 8) { Text(stringResource(R.string.connector_add)) }
    editing?.let { initial ->
        var draft by remember(initial.id) { mutableStateOf(initial) }
        AlertDialog(onDismissRequest = { editing = null }, title = { Text(stringResource(R.string.connector_add)) }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.connector_setup))
                ConnectorField(R.string.connector_name, draft.name) { draft = draft.copy(name = it.take(80)) }
                ConnectorField(R.string.connector_search_url, draft.searchEndpoint) { draft = draft.copy(searchEndpoint = it) }
                ConnectorField(R.string.connector_read_url, draft.readEndpoint) { draft = draft.copy(readEndpoint = it) }
                ConnectorField(R.string.connector_query, draft.queryParameter) { draft = draft.copy(queryParameter = it) }
                ConnectorField(R.string.authorize_url, draft.authorizeEndpoint) { draft = draft.copy(authorizeEndpoint = it) }
                ConnectorField(R.string.exchange_url, draft.tokenEndpoint) { draft = draft.copy(tokenEndpoint = it) }
                ConnectorField(R.string.client_id, draft.clientId) { draft = draft.copy(clientId = it) }
                ConnectorField(R.string.connector_scope, draft.scope) { draft = draft.copy(scope = it) }
            }
        }, confirmButton = { TextButton(onClick = { connect(draft); editing = null }, enabled = runCatching { draft.validate() }.isSuccess && !busy) { Text(stringResource(R.string.continue_browser)) } },
            dismissButton = { TextButton(onClick = { editing = null }) { Text(stringResource(R.string.cancel)) } })
    }
}

@Composable
private fun ConnectorField(label: Int, value: String, update: (String) -> Unit) {
    OutlinedTextField(value, { update(it.take(2048)) }, label = { Text(stringResource(label)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
}
