package app.dsandroid.core.agent

import app.dsandroid.core.llm.ChatMessage
import app.dsandroid.core.tools.WebSource
import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class RunConfig(
    val runId: String = UUID.randomUUID().toString(), val conversationId: String = "",
    val model: String = "", val thinking: Boolean = false, val searchEnabled: Boolean = false,
    val providerKey: String = "",
    val agentEnabled: Boolean = true,
)

/** Contains conversation data only. Credentials must never be put in this record. */
@Serializable
data class RunCheckpoint(
    val runId: String, val conversationId: String = "", val model: String = "",
    val thinking: Boolean = false, val searchEnabled: Boolean = false, val providerKey: String = "",
    val messages: List<ChatMessage> = emptyList(), val steps: List<TurnStep> = emptyList(),
    val pendingToolIndex: Int = 0, val assistantStep: Int = 0,
    val pendingAssistant: ChatMessage? = null, val complete: Boolean = false,
    val agentEnabled: Boolean = true,
    val sources: List<WebSource> = emptyList(), val searchHosts: Set<String> = emptySet(),
)

interface CheckpointStore {
    suspend fun save(checkpoint: RunCheckpoint)
    suspend fun load(runId: String): RunCheckpoint?
    suspend fun delete(runId: String)
}
