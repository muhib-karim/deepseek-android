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
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TasksScreen(state: ChatUiState, vm: ChatViewModel, back: () -> Unit) {
    var creating by remember { mutableStateOf(false) }
    var opened by remember { mutableStateOf<String?>(null) }
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.tasks)) }, navigationIcon = { TextButton(onClick = back) { Text(stringResource(R.string.back)) } }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.tasks_notice))
            Button(onClick = { creating = true }, enabled = !state.running && !state.personalizing) { Text(stringResource(R.string.task_create)) }
            state.scheduledTasks.filter { it.active }.forEach { task ->
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(task.title, style = MaterialTheme.typography.titleMedium)
                        Text(DateFormat.getDateTimeInstance().format(Date(task.runAt)))
                        Text(if (task.intervalMinutes == 0L) stringResource(R.string.task_once) else stringResource(R.string.task_repeats, task.intervalMinutes))
                        TextButton(onClick = { vm.cancelTask(task.id) }, enabled = !state.personalizing && !state.running) { Text(stringResource(R.string.task_cancel)) }
                    }
                }
            }
            Text(stringResource(R.string.tasks_inbox), style = MaterialTheme.typography.titleMedium)
            if (state.taskResults.isEmpty()) Text(stringResource(R.string.tasks_empty))
            state.taskResults.forEach { result ->
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        TextButton(onClick = { opened = if (opened == result.id) null else result.id; if (!result.read) vm.readTask(result.id) }) {
                            Text(if (result.read) result.title else stringResource(R.string.task_unread, result.title))
                        }
                        Text(DateFormat.getDateTimeInstance().format(Date(result.completedAt)), style = MaterialTheme.typography.bodySmall)
                        if (opened == result.id) Text(result.answer, color = if (result.error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
        }
    }
    if (creating) {
        var title by remember { mutableStateOf("") }
        var prompt by remember { mutableStateOf("") }
        var delay by remember { mutableStateOf("60") }
        var interval by remember { mutableStateOf("0") }
        AlertDialog(onDismissRequest = { creating = false }, title = { Text(stringResource(R.string.task_create)) }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.task_confirm_notice))
                OutlinedTextField(title, { title = it.take(100) }, label = { Text(stringResource(R.string.task_title)) })
                OutlinedTextField(prompt, { prompt = it.take(16000) }, label = { Text(stringResource(R.string.task_prompt)) }, maxLines = 8)
                OutlinedTextField(delay, { delay = it.filter(Char::isDigit).take(6) }, label = { Text(stringResource(R.string.task_delay)) })
                OutlinedTextField(interval, { interval = it.filter(Char::isDigit).take(6) }, label = { Text(stringResource(R.string.task_interval)) })
            }
        }, confirmButton = { TextButton(onClick = { vm.schedulePrompt(title, prompt, delay.toLong(), interval.toLong()); creating = false },
            enabled = title.isNotBlank() && prompt.isNotBlank() && delay.toLongOrNull() in 1L..525600L && (interval == "0" || interval.toLongOrNull() in 15L..525600L)) { Text(stringResource(R.string.task_schedule)) } },
            dismissButton = { TextButton(onClick = { creating = false }) { Text(stringResource(R.string.cancel)) } })
    }
}
