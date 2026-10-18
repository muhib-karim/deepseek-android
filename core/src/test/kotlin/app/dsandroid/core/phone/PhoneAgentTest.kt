package app.dsandroid.core.phone

import app.dsandroid.core.agent.*
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

class PhoneAgentTest {
    @get:Rule val temporary = TemporaryFolder()
    private suspend fun exercise(allow: Boolean, agentMode: Boolean = true): Pair<List<JsonObject>, List<PhoneAction>> {
        val previews = mutableListOf<JsonObject>()
        val launches = mutableListOf<PhoneAction>()
        val tool = PhoneActionTool("draft_sms") { launches.add(it); ToolResult("opened") }
        val args = buildJsonObject { put("to", "+123456789"); put("body", "Exact approved text") }
        var calls = 0
        val client = object : LlmClient {
            override fun stream(messages: List<ChatMessage>, tools: List<ToolSpec>) = if (calls++ == 0)
                flowOf(ChatChunk(listOf(StreamChoice(delta = StreamDelta(toolCalls = listOf(ToolCallDelta(0, "phone", function = FunctionDelta(tool.name, args.toString()))))))))
                else flowOf(ChatChunk(listOf(StreamChoice(delta = StreamDelta(content = "Done")))))
        }
        val workspace = WorkspacePath(temporary.newFolder())
        val registry = ToolRegistry(listOf(tool))
        AgentLoop(client, registry, GuardPolicy(registry, workspace), ToolContext(workspace), Confirmer { _, preview -> previews.add(preview); allow }, runConfig = RunConfig(agentEnabled = agentMode))
            .run(mutableListOf(ChatMessage("user", "Draft a message"))).collect()
        return previews to launches
    }
    @Test fun `denial never launches a phone app`() = runTest {
        val (previews, launches) = exercise(false)
        assertEquals("Exact approved text", previews.single()["body"]?.jsonPrimitive?.content)
        assertTrue(launches.isEmpty())
    }
    @Test fun `approved arguments launch once through the real guard and loop`() = runTest {
        val (previews, launches) = exercise(true)
        assertEquals(1, previews.size)
        assertEquals(listOf(PhoneAction.Sms("+123456789", "Exact approved text")), launches)
    }
    @Test fun `chat mode cannot launch unsolicited phone actions`() = runTest {
        val (previews, launches) = exercise(true, false)
        assertTrue(previews.isEmpty())
        assertTrue(launches.isEmpty())
    }
}
