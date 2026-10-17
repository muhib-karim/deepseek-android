package app.dsandroid.core.guard

import app.dsandroid.core.tools.*
import java.io.File
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GuardPolicyTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun tool(name: String, kind: ToolKind, risk: Risk = Risk.SAFE) = object : Tool {
        override val name = name
        override val description = "Test tool"
        override val schema = JsonObject(emptyMap())
        override val kind = kind
        override val risk = risk
        override suspend fun run(args: JsonObject, ctx: ToolContext) = ToolResult("ok")
    }
    @Test fun checksEveryDeclaredPathInsteadOfTrustingAnExtraSafePath() {
        val root = temporary.newFolder("workspace")
        val registry = ToolRegistry(listOf(tool("write", ToolKind.WRITE)))
        val guard = GuardPolicy(registry, WorkspacePath(root))
        val args = buildJsonObject {
            put("path", "safe.md")
            put("fileName", File(temporary.root, "outside.md").path)
        }
        assertEquals(Decision.CONFIRM, guard.decide("write", args, GuardSettings()))
        val malicious = buildJsonObject { put("path", "safe.md"); put("fileName", "../outside.md") }
        assertEquals(Decision.DENY, guard.decide("write", malicious, GuardSettings()))
    }
    @Test fun decisionTable() {
        val root = temporary.newFolder("workspace")
        val guard = GuardPolicy(ToolRegistry(listOf(tool("write", ToolKind.WRITE), tool("read", ToolKind.READ),
            tool("shell", ToolKind.SHELL), tool("install", ToolKind.INSTALL), tool("confirm", ToolKind.ACTION, Risk.CONFIRM))), WorkspacePath(root))
        data class Case(val tool: String, val path: String?, val settings: GuardSettings, val expected: Decision)
        val defaults = GuardSettings()
        val enabled = GuardSettings(shellEnabled = true, installEnabled = true)
        listOf(
            Case("write", "note.md", defaults, Decision.ALLOW),
            Case("write", File(root, "note.md").path, defaults, Decision.ALLOW),
            Case("write", File(temporary.root, "outside.md").path, defaults, Decision.CONFIRM),
            Case("write", "../outside.md", defaults, Decision.DENY),
            Case("write", "a\\..\\outside.md", defaults, Decision.DENY),
            Case("write", null, defaults, Decision.DENY),
            Case("read", "note.md", defaults, Decision.ALLOW),
            Case("read", File(temporary.root, "outside.md").path, defaults, Decision.DENY),
            Case("shell", null, defaults, Decision.DENY), Case("install", null, defaults, Decision.DENY),
            Case("shell", null, enabled, Decision.CONFIRM), Case("install", null, enabled, Decision.CONFIRM),
            Case("confirm", null, defaults, Decision.CONFIRM), Case("unknown", null, enabled, Decision.DENY)
        ).forEach { case ->
            val args = buildJsonObject { case.path?.let { put("path", it) } }
            assertEquals(case.toString(), case.expected, guard.decide(case.tool, args, case.settings))
        }
        repeat(2) { assertEquals(Decision.CONFIRM, guard.decide("shell", JsonObject(emptyMap()), enabled)) }
    }
}
