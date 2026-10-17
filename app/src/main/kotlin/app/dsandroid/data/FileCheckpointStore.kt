package app.dsandroid.data

import app.dsandroid.core.agent.CheckpointStore
import app.dsandroid.core.agent.RunCheckpoint
import app.dsandroid.core.llm.LlmJson
import app.dsandroid.core.persistence.JsonFileStore
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

class FileCheckpointStore(directory: File) : CheckpointStore {
    private val files = JsonFileStore(directory)
    override suspend fun save(checkpoint: RunCheckpoint) = withContext(Dispatchers.IO) {
        files.write(checkpoint.runId, LlmJson.encodeToString(checkpoint))
    }
    override suspend fun load(runId: String): RunCheckpoint? = withContext(Dispatchers.IO) {
        files.read(runId)?.let { LlmJson.decodeFromString<RunCheckpoint>(it) }?.also { require(it.runId == runId) }
    }
    override suspend fun delete(runId: String) = withContext(Dispatchers.IO) { files.delete(runId) }
    suspend fun unfinished(): List<RunCheckpoint> = all().filterNot { it.complete }
    suspend fun all(): List<RunCheckpoint> = withContext(Dispatchers.IO) {
        files.ids().mapNotNull { id ->
            runCatching { files.read(id)?.let { LlmJson.decodeFromString<RunCheckpoint>(it) } }
                .getOrNull()?.takeIf { it.runId == id }
        }
    }
    suspend fun deleteConversation(conversationId: String) {
        all().filter { it.conversationId == conversationId }.forEach { delete(it.runId) }
    }
}
