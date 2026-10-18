package app.dsandroid.core.agent

import app.dsandroid.core.browser.PreviewTool
import app.dsandroid.core.guard.*
import app.dsandroid.core.llm.*
import app.dsandroid.core.tools.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PassEAgentTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun rateLimitIsSurfacedWithoutRetryWhenManualPolicyEnabled() = runTest {
        var attempts = 0
        val client = object : LlmClient {
            override fun stream(messages: List<ChatMessage>, tools: List<ToolSpec>): Flow<ChatChunk> = flow { attempts++; throw ProviderHttpException(429, "rate limit") }
        }
        val workspace = WorkspacePath(temporary.newFolder()); val registry = ToolRegistry(emptyList())
        val events = AgentLoop(client, registry, GuardPolicy(registry, workspace), ToolContext(workspace), Confirmer { _, _ -> true }, manualRateLimit = true)
            .run(mutableListOf(ChatMessage("user", "Hello"))).toList()
        assertEquals(1, attempts)
        assertTrue(events.last() is AgentEvent.Error)
        assertTrue(events.none { it is AgentEvent.RetryWaiting })
    }
    @Test fun exactPreviewReachesConfirmationAndDenialPreventsExecution() = runTest {
        var ran = false; var previewSeen = false; var count = 0
        val preview = buildJsonObject { put("destination", "https://example.com/approved"); put("text", "exact text") }
        val tool = object : PreviewTool {
            override val name = "preview_action"
            override val description = "Preview fixture"
            override val schema = objectSchema(emptyMap())
            override val risk = Risk.CONFIRM
            override val kind = ToolKind.NETWORK
            override suspend fun confirmationArguments(args: JsonObject, ctx: ToolContext) = preview
            override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult { ran = true; return ToolResult("done") }
        }
        val client = object : LlmClient {
            override fun stream(messages: List<ChatMessage>, tools: List<ToolSpec>) = if (count++ == 0)
                flowOf(ChatChunk(listOf(StreamChoice(delta = StreamDelta(toolCalls = listOf(ToolCallDelta(0, "call", function = FunctionDelta(tool.name, "{}"))))))))
                else flowOf(ChatChunk(listOf(StreamChoice(delta = StreamDelta(content = "Denied")))))
        }
        val workspace = WorkspacePath(temporary.newFolder()); val registry = ToolRegistry(listOf(tool))
        AgentLoop(client, registry, GuardPolicy(registry, workspace), ToolContext(workspace), Confirmer { _, args -> previewSeen = args == preview; false })
            .run(mutableListOf(ChatMessage("user", "Act"))).collect()
        assertTrue(previewSeen); assertFalse(ran)
    }
}
