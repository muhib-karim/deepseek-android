package app.dsandroid.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.dsandroid.R
import app.dsandroid.core.agents.SubAgentStatus

@Composable
fun SubAgentsCard(state: ChatUiState) {
    if (state.subAgents.isEmpty()) return
    OutlinedCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.parallel_agents), style = MaterialTheme.typography.titleMedium)
            Text(state.agentPlan, maxLines = 5)
            state.subAgents.forEach { agent ->
                Text(stringResource(R.string.agent_progress, agent.title, stringResource(when (agent.status) {
                    SubAgentStatus.QUEUED -> R.string.agent_queued
                    SubAgentStatus.RUNNING -> R.string.agent_running
                    SubAgentStatus.DONE -> R.string.agent_done
                    SubAgentStatus.ERROR -> R.string.agent_failed
                    SubAgentStatus.CANCELLED -> R.string.agent_cancelled
                })))
                if (agent.steps > 0) Text(stringResource(R.string.agent_steps, agent.steps), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
