package app.dsandroid.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.dsandroid.R
import app.dsandroid.core.memory.MemoryChange

@Composable
fun MemoryUpkeepSettings(enabled: Boolean, toggle: (Boolean) -> Unit, state: ChatUiState, vm: ChatViewModel) {
    Row {
        Text(stringResource(R.string.memory_upkeep), Modifier.weight(1f))
        Switch(enabled, toggle, enabled = !state.running && !state.saving)
    }
    Text(stringResource(R.string.memory_upkeep_notice), style = MaterialTheme.typography.bodySmall)
    state.memorySuggestions.forEach { suggestion ->
        OutlinedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(when (suggestion.kind) { MemoryChange.ADD -> R.string.memory_propose_add; MemoryChange.EDIT -> R.string.memory_propose_edit; MemoryChange.DELETE -> R.string.memory_propose_delete }))
                if (suggestion.before.isNotBlank()) Text(stringResource(R.string.memory_before, suggestion.before))
                if (suggestion.text.isNotBlank()) Text(stringResource(R.string.memory_after, suggestion.text))
                Text(suggestion.reason, style = MaterialTheme.typography.bodySmall)
                Row {
                    TextButton(onClick = { vm.reviewMemory(suggestion.id, false) }, enabled = !state.personalizing && !state.running) { Text(stringResource(R.string.memory_reject)) }
                    Button(onClick = { vm.reviewMemory(suggestion.id, true) }, enabled = !state.personalizing && !state.running) { Text(stringResource(R.string.memory_approve)) }
                }
            }
        }
    }
}
