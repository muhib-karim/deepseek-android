package app.dsandroid.core.tasks

import app.dsandroid.core.llm.ChatMessage
import app.dsandroid.core.llm.LlmClient
import app.dsandroid.core.llm.StreamAccumulator
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withTimeout

/** One bounded inference, bound to the selected account, with no tools or chat history. */
object ScheduledPromptRunner {
    suspend fun answer(task: ScheduledTask, activeProviderKey: String, client: () -> LlmClient): String {
        require(task.providerKey == activeProviderKey) { "Original account must be selected manually" }
        val accumulator = StreamAccumulator()
        var characters = 0
        withTimeout(120_000) {
            client().stream(listOf(ChatMessage("system", "You are answering a scheduled prompt in DeepSeek Android. No tools, browser or device actions are available. Do not claim to have accessed current information or performed actions. Return one self-contained answer."),
                ChatMessage("user", task.prompt)), emptyList()).collect { chunk ->
                chunk.choices.filter { it.index == 0 }.forEach { choice ->
                    require(choice.delta.toolCalls.isNullOrEmpty()) { "Scheduled prompts cannot run tools" }
                    characters += choice.delta.content.orEmpty().length + choice.delta.reasoningContent.orEmpty().length
                    require(characters <= 32000) { "Scheduled answer exceeds output limit" }
                }
                accumulator.accept(chunk)
            }
        }
        return requireNotNull(accumulator.message().content).also { require(it.isNotBlank()) }
    }
}
