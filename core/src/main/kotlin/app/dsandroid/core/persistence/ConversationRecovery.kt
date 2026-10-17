package app.dsandroid.core.persistence

import app.dsandroid.core.agent.RunCheckpoint
import app.dsandroid.core.agent.TurnStep
import app.dsandroid.core.llm.LlmJson
import app.dsandroid.core.tools.ToolResult
import kotlinx.serialization.decodeFromString

/** Reconciles the atomic run record with the independently saved drawer history. */
object ConversationRecovery {
    fun apply(record: ConversationRecord, checkpoint: RunCheckpoint): ConversationRecord {
        if (checkpoint.runId in record.supersededRunIds) return record
        val toolIds = checkpoint.steps.filterIsInstance<TurnStep.ToolCall>().map { it.id }.toSet()
        val artifacts = checkpoint.messages.filter { it.role == "tool" && it.toolCallId in toolIds }.flatMap {
            runCatching { LlmJson.decodeFromString<ToolResult>(it.content.orEmpty()).artifacts }.getOrDefault(emptyList())
        }.distinctBy { it.path }
        val assistant = StoredChatItem(checkpoint.runId, "assistant", steps = checkpoint.steps, artifacts = artifacts, sources = checkpoint.sources)
        val items = if (record.items.any { it.id == checkpoint.runId }) record.items.map { if (it.id == checkpoint.runId) assistant else it }
            else record.items + assistant
        return record.copy(history = checkpoint.messages, items = items, pendingRunId = checkpoint.runId.takeUnless { checkpoint.complete })
    }

    fun reconcile(records: List<ConversationRecord>, checkpoints: List<RunCheckpoint>, nowMs: Long): List<ConversationRecord> {
        val byId = checkpoints.associateBy { it.runId }
        val recovered = records.map { record ->
            val checkpoint = record.pendingRunId?.takeUnless { it in record.supersededRunIds }?.let(byId::get)
                ?: checkpoints.lastOrNull { it.conversationId == record.id && !it.complete && it.runId !in record.supersededRunIds }
            if (checkpoint != null) apply(record, checkpoint) else record.copy(pendingRunId = null)
        }.toMutableList()
        for ((conversationId, runs) in checkpoints.filter { it.conversationId.isNotBlank() }.groupBy { it.conversationId }) {
            if (recovered.any { it.id == conversationId }) continue
            val checkpoint = runs.lastOrNull { !it.complete } ?: runs.last()
            val users = checkpoint.messages.filter { it.role == "user" }.mapIndexed { index, message ->
                StoredChatItem("${checkpoint.runId}-user-$index", "user", message.content
                    ?: message.parts.orEmpty().mapNotNull { it.text }.joinToString("\n"))
            }
            val title = users.firstOrNull()?.text.orEmpty().replace('\n', ' ').take(60)
            recovered.add(apply(ConversationRecord(conversationId, title, nowMs, checkpoint.messages, users), checkpoint))
        }
        return recovered.sortedByDescending { it.createdAt }
    }
}
