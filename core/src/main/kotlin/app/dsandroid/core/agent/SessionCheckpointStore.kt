package app.dsandroid.core.agent

/** Temporary runs can retry while alive but never enter the disk recovery journal. */
class SessionCheckpointStore(private val persistent: CheckpointStore) : CheckpointStore {
    private val temporary = mutableSetOf<String>()
    private val volatileRuns = mutableMapOf<String, RunCheckpoint>()
    @Synchronized fun markTemporary(conversationId: String) { temporary.add(conversationId) }
    @Synchronized fun discardTemporary(conversationId: String) {
        volatileRuns.entries.removeAll { it.value.conversationId == conversationId }
        temporary.remove(conversationId)
    }
    override suspend fun save(checkpoint: RunCheckpoint) {
        val ephemeral = synchronized(this) {
            if (checkpoint.conversationId in temporary) { volatileRuns[checkpoint.runId] = checkpoint; true } else false
        }
        if (!ephemeral) persistent.save(checkpoint)
    }
    override suspend fun load(runId: String): RunCheckpoint? = synchronized(this) { volatileRuns[runId] } ?: persistent.load(runId)
    override suspend fun delete(runId: String) {
        val removed = synchronized(this) { volatileRuns.remove(runId) != null }
        if (!removed) persistent.delete(runId)
    }
}
