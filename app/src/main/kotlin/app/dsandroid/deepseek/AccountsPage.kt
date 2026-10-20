package app.dsandroid.deepseek

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.dsandroid.core.accounts.AccountType

/**
 * Settings > Accounts: every DeepSeek account and API-key profile with its sign-in type, balance and when the balance
 * was read. One tap on a row switches; nothing else changes the account in use (docs/ACCOUNTS.md).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AccountsPage(state: AccountUiState, actions: AccountActions, modifier: Modifier = Modifier) {
    var adding by remember { mutableStateOf(false) }
    var label by remember { mutableStateOf("") }
    var key by remember { mutableStateOf("") }
    var renaming by remember { mutableStateOf<AccountRow?>(null) }
    var renameText by remember { mutableStateOf("") }
    var removing by remember { mutableStateOf<AccountRow?>(null) }
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Your accounts", style = MaterialTheme.typography.titleMedium)
        Text("Tap an account to use it. The app never switches, rotates or falls back to another account by itself, not even after a quota " +
            "or rate error. Each account keeps its own encrypted sign-in or key and its own chats.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (state.balanceBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (state.accounts.isEmpty()) Text("No accounts yet. Add a DeepSeek account or an API-key profile.", style = MaterialTheme.typography.bodySmall)
        state.accounts.forEach { row ->
            AccountRowView(row, enabled = !state.busy, balanceBusy = state.balanceBusy, onSwitch = { actions.switchTo(row.id) },
                onRefresh = { actions.refreshBalances(row.id) }, onRename = { renaming = row; renameText = row.label }, onRemove = { removing = row })
        }
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            OutlinedButton(onClick = actions::addDeepSeekAccount, enabled = !state.busy, modifier = Modifier.testTag("add-deepseek-account")) { Text("Add DeepSeek account") }
            OutlinedButton(onClick = { label = ""; key = ""; adding = true }, enabled = !state.busy, modifier = Modifier.testTag("add-api-key-profile")) {
                Text("Add API-key profile")
            }
        }
        TextButton(onClick = { actions.refreshBalances(null) }, enabled = !state.balanceBusy && state.accounts.any { it.type == AccountType.API_KEY },
            modifier = Modifier.testTag("refresh-balances")) { Text("Refresh balances") }
        Text("Balances come from DeepSeek's documented GET /user/balance, read with each API key only when you tap Refresh. DeepSeek's web chat " +
            "has no documented balance or quota endpoint, so web sign-ins show “not available”.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (adding) AlertDialog(onDismissRequest = { key = ""; adding = false }, title = { Text("Add API-key profile") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(label, { label = it.take(80) }, label = { Text("Label, e.g. Work") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("profile-label"))
                OutlinedTextField(key, { key = it.take(16_384) }, label = { Text("DeepSeek API key") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth().testTag("profile-key"))
                Text("Stored encrypted on this phone. It is used only after you tap the profile.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(onClick = { actions.addApiKeyProfile(label, key); key = ""; adding = false }, enabled = key.isNotBlank(),
                modifier = Modifier.testTag("save-profile")) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = { key = ""; adding = false }) { Text("Cancel") } })
    renaming?.let { row ->
        AlertDialog(onDismissRequest = { renaming = null }, title = { Text("Rename account") },
            text = { OutlinedTextField(renameText, { renameText = it.take(80) }, singleLine = true, label = { Text("Label") }, modifier = Modifier.testTag("account-label")) },
            confirmButton = { TextButton(onClick = { renaming = null; actions.rename(row.id, renameText) }, enabled = renameText.isNotBlank()) { Text("Save") } },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } })
    }
    removing?.let { row ->
        AlertDialog(onDismissRequest = { removing = null }, title = { Text("Remove ${row.label}?") },
            text = { Text(if (row.type == AccountType.DEEPSEEK_WEB) "This removes its sign-in, chats and files from this phone. Your DeepSeek account itself is not changed." +
                (if (row.active) " No other account is selected in its place." else "")
                else "This removes the saved key from this phone." + if (row.active) " No other account is selected in its place." else "") },
            confirmButton = { TextButton(onClick = { removing = null; actions.remove(row.id) }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Cancel") } })
    }
}

@Composable
private fun AccountRowView(row: AccountRow, enabled: Boolean, balanceBusy: Boolean, onSwitch: () -> Unit, onRefresh: () -> Unit, onRename: () -> Unit,
    onRemove: () -> Unit) {
    Surface(Modifier.fillMaxWidth().testTag("account-row-${row.id}"), shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(if (row.active) 1.5.dp else 0.5.dp, if (row.active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)) {
        Column {
            Row(Modifier.fillMaxWidth().clickable(enabled = enabled && !row.active, onClickLabel = "Switch to ${row.label}", onClick = onSwitch)
                .padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = row.active, onClick = null)
                Column(Modifier.weight(1f).padding(start = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(row.label, fontWeight = FontWeight.Medium)
                    val status = when {
                        row.type == AccountType.API_KEY -> ""
                        row.signedIn -> " · signed in"
                        else -> " · signed out, tap to sign in again"
                    }
                    Text(row.type.label + status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(row.balance, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("account-balance-${row.id}"))
                    row.lastRead?.let { Text(it, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                if (row.active) Text("In use", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .6f), thickness = 0.5.dp)
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
                if (row.type == AccountType.API_KEY) TextButton(onClick = onRefresh, enabled = !balanceBusy) { Text("Refresh") }
                TextButton(onClick = onRename, enabled = enabled) { Text("Rename") }
                TextButton(onClick = onRemove, enabled = enabled) { Text("Remove") }
            }
        }
    }
}
