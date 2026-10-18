package app.dsandroid.core.phone

import app.dsandroid.core.guard.*
import app.dsandroid.core.tools.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PhoneActionsTest {
    @get:Rule val folder = TemporaryFolder()
    private fun sms(to: String = "+880 123456789", body: String = "Hello & goodbye") = buildJsonObject { put("to", to); put("body", body) }
    @Test fun `all phone actions require confirmation and alarms are not registered`() {
        val tools = PhoneActionTool.NAMES.map { PhoneActionTool(it) { ToolResult("opened") } }
        val policy = GuardPolicy(ToolRegistry(tools), WorkspacePath(folder.root))
        assertTrue(tools.all { it.risk == Risk.CONFIRM && it.kind == ToolKind.ACTION })
        assertEquals(Decision.CONFIRM, policy.decide("draft_sms", sms(), GuardSettings()))
        assertEquals(Decision.DENY, policy.decide("set_alarm", buildJsonObject {}, GuardSettings()))
    }
    @Test fun `SMS body remains opaque data`() {
        val action = PhoneActions.parse("draft_sms", sms()) as PhoneAction.Sms
        assertEquals("Hello & goodbye", action.body)
        assertEquals("+880 123456789", action.to)
    }
    @Test fun `intent URI and recipient injection are denied before confirmation`() {
        val policy = GuardPolicy(ToolRegistry(listOf(PhoneActionTool("draft_sms") { ToolResult("opened") })), WorkspacePath(folder.root))
        listOf("intent://launch", "123?body=bad", "123;456", "123\n456").forEach {
            assertEquals(Decision.DENY, policy.decide("draft_sms", sms(it), GuardSettings()))
        }
    }
    @Test fun `unknown fields cannot add flags or intent components`() {
        val args = JsonObject(sms().toMutableMap().apply { put("component", JsonPrimitive("external")) })
        assertThrows(IllegalArgumentException::class.java) { PhoneActions.parse("draft_sms", args) }
    }
    @Test fun `email rejects header and multi recipient injection`() {
        listOf("a@example.org?bcc=b@example.org", "a@example.org\r\nBcc:x", "a@example.org,b@example.org").forEach { to ->
            assertThrows(IllegalArgumentException::class.java) {
                PhoneActions.parse("draft_email", buildJsonObject { put("to", to); put("subject", "Hello"); put("body", "Message") })
            }
        }
    }
    @Test fun `calendar requires valid ordered epoch milliseconds`() {
        fun args(start: JsonPrimitive, end: Long) = buildJsonObject {
            put("title", "Meeting"); put("beginMillis", start); put("endMillis", end); put("location", "Room"); put("description", "Notes")
        }
        assertThrows(IllegalArgumentException::class.java) { PhoneActions.parse("add_calendar_event", args(JsonPrimitive(2000), 1000)) }
        assertThrows(IllegalArgumentException::class.java) { PhoneActions.parse("add_calendar_event", args(JsonPrimitive("1000"), 2000)) }
        val event = PhoneActions.parse("add_calendar_event", args(JsonPrimitive(1000), 2000)) as PhoneAction.Calendar
        assertEquals(2000L, event.endMillis)
    }
    @Test fun `map search stays a query including Unicode and URI punctuation`() {
        val query = "ঢাকা & q=another # place"
        val action = PhoneActions.parse("open_map", buildJsonObject { put("query", query) }) as PhoneAction.Map
        assertEquals(query, action.query)
    }
    @Test fun `empty or oversized payloads fail before launch`() {
        assertThrows(IllegalArgumentException::class.java) { PhoneActions.parse("open_map", buildJsonObject { put("query", " ") }) }
        assertThrows(IllegalArgumentException::class.java) { PhoneActions.parse("draft_sms", sms(body = "a".repeat(8001))) }
    }
    @Test fun `tool delegates exactly once with typed data`() = runTest {
        val actions = mutableListOf<PhoneAction>()
        val tool = PhoneActionTool("draft_sms") { actions.add(it); ToolResult("Draft opened") }
        assertEquals("Draft opened", tool.run(sms(), ToolContext(WorkspacePath(folder.root))).content)
        assertEquals(listOf(PhoneAction.Sms("+880 123456789", "Hello & goodbye")), actions)
    }
}
