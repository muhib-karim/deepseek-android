package app.dsandroid.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.dsandroid.R
import app.dsandroid.data.AppSettings

@Composable
fun AccountPicker(settings: AppSettings, busy: Boolean, vm: ChatViewModel) {
    var adding by remember { mutableStateOf(false) }
    var label by remember { mutableStateOf("") }
    var key by remember { mutableStateOf("") }
    var deleting by remember { mutableStateOf<String?>(null) }
    Text(stringResource(R.string.accounts), style = MaterialTheme.typography.titleMedium)
    Text(stringResource(R.string.accounts_notice))
    settings.accounts.forEach { account ->
        Row(Modifier.fillMaxWidth()) {
            TextButton(onClick = { vm.selectAccount(account.id) }, enabled = !busy, modifier = Modifier.weight(1f)) {
                Text(if (settings.activeAccountId == account.id) stringResource(R.string.account_selected, account.label) else account.label)
            }
            TextButton(onClick = { deleting = account.id }, enabled = !busy) { Text(stringResource(R.string.delete)) }
        }
    }
    TextButton(onClick = { vm.saveCurrentAccount(label.ifBlank { "" }) }, enabled = !busy && settings.activeAccountId == null && (settings.apiKey.isNotBlank() || settings.accountToken.isNotBlank())) { Text(stringResource(R.string.account_save_current)) }
    Button(onClick = { label = ""; key = ""; adding = true }, enabled = !busy && settings.accounts.size < 20) { Text(stringResource(R.string.account_add)) }
    if (adding) AlertDialog(onDismissRequest = { key = ""; adding = false }, title = { Text(stringResource(R.string.account_add)) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(label, { label = it.take(80) }, label = { Text(stringResource(R.string.account_label)) })
            OutlinedTextField(key, { key = it.take(16384) }, label = { Text(stringResource(R.string.api_key)) }, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), singleLine = true)
        }
    }, confirmButton = { TextButton(onClick = { vm.addAccount(label, key); key = ""; adding = false }, enabled = label.isNotBlank() && key.isNotBlank() && !busy) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = { key = ""; adding = false }) { Text(stringResource(R.string.cancel)) } })
    deleting?.let { id -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text(stringResource(R.string.delete)) }, text = { Text(stringResource(R.string.account_delete_notice)) },
        confirmButton = { TextButton(onClick = { vm.deleteAccount(id); deleting = null }) { Text(stringResource(R.string.delete)) } },
        dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.cancel)) } }) }
}
