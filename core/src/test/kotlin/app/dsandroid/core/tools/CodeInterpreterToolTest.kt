package app.dsandroid.core.tools

import app.dsandroid.core.guard.WorkspacePath
import app.dsandroid.core.sandbox.CodeExecution
import app.dsandroid.core.sandbox.CodeExecutor
import app.dsandroid.core.sandbox.CodeLanguage
import app.dsandroid.core.sandbox.CodeLimits
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CodeInterpreterToolTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun context() = ToolContext(WorkspacePath(temporary.newFolder()))

    @Test fun returnsSeparateBoundedChannelsAndPreservesUndefinedVersusNull() = runBlocking {
        val tool = CodeInterpreterTool(CodeExecutor { request ->
            check(request.language == CodeLanguage.JAVASCRIPT)
            check(request.code == "return null")
            CodeExecution(stdout = "hello", value = "null")
        })
        val result = tool.run(buildJsonObject { put("language", "javascript"); put("code", "return null") }, context())
        val json = Json.parseToJsonElement(result.content).jsonObject
        assertFalse(result.isError)
        assertEquals("hello", json["stdout"]!!.jsonPrimitive.content)
        assertEquals("null", json["value"]!!.jsonPrimitive.content)
        assertTrue(json["hasValue"]!!.jsonPrimitive.boolean)
        val undefined = CodeInterpreterTool(CodeExecutor { CodeExecution() })
            .run(buildJsonObject { put("language", "javascript"); put("code", "1 + 1") }, context())
        assertFalse(Json.parseToJsonElement(undefined.content).jsonObject["hasValue"]!!.jsonPrimitive.boolean)
    }

    @Test fun rejectsUnsupportedOrOversizedCodeBeforeExecutorCanRun() = runBlocking {
        val tool = CodeInterpreterTool(CodeExecutor { error("Invalid request reached execution") })
        for (args in listOf(
            buildJsonObject { put("language", "shell"); put("code", "anything") },
            buildJsonObject { put("language", "javascript"); put("code", "x".repeat(CodeLimits.MAX_CODE_CHARS + 1)) },
            buildJsonObject { put("language", "javascript"); put("code", " ") },
            buildJsonObject { put("language", "javascript"); put("code", "return 1"); put("url", "https://example.com") }
        )) {
            try { tool.run(args, context()); fail("Invalid code request accepted") }
            catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun capsTotalNativeOutputEvenIfAnExecutorViolatesTheContract() = runBlocking {
        val tool = CodeInterpreterTool(CodeExecutor {
            CodeExecution(stdout = "a".repeat(20_000), stderr = "b".repeat(20_000), value = "c".repeat(20_000))
        })
        val result = tool.run(buildJsonObject { put("language", "python"); put("code", "1") }, context())
        val json = Json.parseToJsonElement(result.content).jsonObject
        val total = listOf("stdout", "stderr", "value").sumOf { json[it]!!.jsonPrimitive.content.length }
        assertTrue(total <= CodeLimits.MAX_OUTPUT_CHARS)
        assertTrue(json["truncated"]!!.jsonPrimitive.boolean)
    }

    @Test fun reportsTimeoutAsErrorAndPropagatesCancellation() = runBlocking {
        val args = buildJsonObject { put("language", "javascript"); put("code", "while(true){}") }
        val timeout = CodeInterpreterTool(CodeExecutor { CodeExecution(timedOut = true, failed = true) }).run(args, context())
        assertTrue(timeout.isError)
        assertTrue(Json.parseToJsonElement(timeout.content).jsonObject["timedOut"]!!.jsonPrimitive.boolean)
        try {
            CodeInterpreterTool(CodeExecutor { throw CancellationException("Stopped") }).run(args, context())
            fail("Cancellation swallowed")
        } catch (_: CancellationException) { }
    }
}
