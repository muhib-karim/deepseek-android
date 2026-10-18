package app.dsandroid.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import app.dsandroid.R
import app.dsandroid.core.backup.*
import kotlinx.coroutines.*

@Composable
fun BackupSettingsPanel(state: ChatUiState, vm: ChatViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pendingExport by remember { mutableStateOf<String?>(null) }
    var pendingRestore by remember { mutableStateOf<BackupArchive?>(null) }
    var working by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<Int?>(null) }
    val busy = working || state.backupBusy
    BackHandler(busy) { /* Keep the transfer and its result visible. */ }
    val create = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val content = pendingExport
        pendingExport = null
        if (uri != null) scope.launch {
            working = true
            try {
                require(uri.scheme == "content" && content != null)
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(content.toByteArray(Charsets.UTF_8)) }
                        ?: error("No output stream")
                }
                message = R.string.backup_exported
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { message = R.string.backup_failed }
            finally { working = false }
        }
    }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            working = true
            try {
                require(uri.scheme == "content")
                pendingRestore = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use(BackupCodec::read) ?: error("No input stream")
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { message = R.string.backup_invalid }
            finally { working = false }
        }
    }
    Text(stringResource(R.string.backup_notice))
    Text(stringResource(R.string.backup_contents), style = MaterialTheme.typography.bodySmall)
    Button(onClick = {
        vm.exportBackup(AppCompatDelegate.getApplicationLocales().toLanguageTags()) { content ->
            pendingExport = content
            try { create.launch("deepseek-backup.json") }
            catch (_: Exception) { pendingExport = null; message = R.string.backup_failed }
        }
    }, enabled = !busy && !state.saving && !state.running, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.backup_export)) }
    OutlinedButton(onClick = {
        try { open.launch(arrayOf("application/json", "application/octet-stream")) }
        catch (_: Exception) { message = R.string.backup_failed }
    }, enabled = !busy && !state.saving && !state.running, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.backup_restore)) }
    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    message?.let { Text(stringResource(it)) }
    pendingRestore?.let { archive ->
        AlertDialog(onDismissRequest = { pendingRestore = null },
            title = { Text(stringResource(R.string.backup_confirm_title)) },
            text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.backup_confirm_notice))
                Text(stringResource(R.string.backup_counts, archive.chats.size, archive.projects.size, archive.memory.size, archive.personas.size, archive.tasks.size))
            } },
            confirmButton = { TextButton(onClick = {
                pendingRestore = null
                vm.restoreBackup(archive) {
                    message = R.string.backup_restored
                    AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(archive.settings.appLanguage))
                }
            }, enabled = !busy) { Text(stringResource(R.string.backup_merge)) } },
            dismissButton = { TextButton(onClick = { pendingRestore = null }) { Text(stringResource(R.string.cancel)) } })
    }
}
