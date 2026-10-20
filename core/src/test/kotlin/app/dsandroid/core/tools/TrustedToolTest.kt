package app.dsandroid.core.tools

import app.dsandroid.core.guard.Decision
import app.dsandroid.core.guard.GuardPolicy
import app.dsandroid.core.guard.GuardSettings
import app.dsandroid.core.guard.WorkspacePath
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TrustedToolTest {
    @get:Rule val temporary = TemporaryFolder()

    private class Compute : Tool {
        var runs = 0
        override val name = "run_python"
        override val description = "Run Python."
        override val schema = objectSchema(mapOf("code" to stringSchema("Python")))
        override val risk = Risk.SAFE
        override val kind = ToolKind.COMPUTE
        override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult { runs++; return ToolResult("4") }
    }

    @Test fun aTrustedToolKeepsItsContractButAlwaysAsksFirst() = runBlocking<Unit> {
        val inner = Compute()
        val trusted = TrustedTool(inner)
        assertEquals("run_python", trusted.name)
        assertEquals(inner.schema, trusted.schema)
        assertEquals(ToolKind.COMPUTE, trusted.kind)
        assertEquals(Risk.CONFIRM, trusted.risk)
        assertTrue(trusted.description.startsWith("Run Python.") && trusted.description.contains("not in a sandbox"))
        val workspace = WorkspacePath(temporary.newFolder())
        val args = buildJsonObject { put("code", "print(2 + 2)") }
        assertEquals(Decision.ALLOW, GuardPolicy(ToolRegistry(listOf(inner)), workspace).decide("run_python", args, GuardSettings()))
        assertEquals(Decision.CONFIRM, GuardPolicy(ToolRegistry(listOf(trusted)), workspace).decide("run_python", args, GuardSettings()))
        assertEquals("4", trusted.run(args, ToolContext(workspace)).content)
        assertEquals(1, inner.runs)
    }
}
