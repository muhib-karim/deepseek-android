package app.dsandroid.core.agent

import app.dsandroid.core.llm.FunctionSpec
import app.dsandroid.core.llm.ToolSpec
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ToolCallParserTest {
    private val fence = "```"

    private fun call(text: String, known: Set<String> = emptySet()): ToolCallParse.Call {
        val parsed = ToolCallProtocol.parse(text, known)
        assertTrue("Expected a call but got $parsed", parsed is ToolCallParse.Call)
        return parsed as ToolCallParse.Call
    }

    private fun malformed(text: String, known: Set<String> = emptySet()): ToolCallParse.Malformed {
        val parsed = ToolCallProtocol.parse(text, known)
        assertTrue("Expected malformed but got $parsed", parsed is ToolCallParse.Malformed)
        return parsed as ToolCallParse.Malformed
    }

    @Test fun validBlockAtTheEndIsACallWithTheProseBeforeIt() {
        val parsed = call("I will create the report now.\n\n${fence}tool\n{\"name\": \"create_pdf\", \"arguments\": {\"title\": \"Q3\", \"markdown\": \"# Q3\"}}\n$fence")
        assertEquals("I will create the report now.", parsed.prose)
        assertEquals("create_pdf", parsed.name)
        assertEquals("Q3", parsed.arguments["title"]!!.jsonPrimitive.content)
        assertEquals("# Q3", parsed.arguments["markdown"]!!.jsonPrimitive.content)
    }

    @Test fun toleratesWhitespaceAroundFencesJsonAndLineEndings() {
        val parsed = call("Sure.\r\n   $fence  tool  \r\n\r\n   {\n  \"name\" :  \"read\" ,\n  \"arguments\" : { \"file_path\" : \"a.txt\" }\n}   \r\n\r\n  $fence   \r\n\n  ")
        assertEquals("Sure.", parsed.prose)
        assertEquals("read", parsed.name)
        assertEquals(JsonPrimitive("a.txt"), parsed.arguments["file_path"])
    }

    @Test fun replyWithoutToolBlockIsPlainText() {
        val text = "The answer is 4.\n\n${fence}python\nprint(2 + 2)\n$fence"
        assertEquals(ToolCallParse.Plain(text), ToolCallProtocol.parse(text))
        assertEquals(ToolCallParse.Plain(""), ToolCallProtocol.parse(""))
    }

    @Test fun ordinaryCodeBlockBeforeTheToolBlockStaysInTheProse() {
        val parsed = call("Here is the code:\n${fence}python\nprint(1)\n$fence\nRunning it.\n${fence}tool\n{\"name\":\"run_python\",\"arguments\":{\"code\":\"print(1)\"}}\n$fence")
        assertTrue(parsed.prose.contains("${fence}python\nprint(1)\n$fence"))
        assertTrue(parsed.prose.endsWith("Running it."))
        assertEquals("run_python", parsed.name)
    }

    @Test fun invalidJsonIsMalformed() {
        val result = malformed("${fence}tool\n{\"name\": \"read\", \"arguments\": {\"file_path\": a.txt}}\n$fence")
        assertTrue(result.reason.contains("JSON"))
    }

    @Test fun unclosedFenceOrObjectIsMalformed() {
        assertTrue(malformed("Working\n${fence}tool\n{\"name\": \"read\", \"arguments\": {}}").reason.contains("closed"))
        assertTrue(malformed("${fence}tool\n{\"name\": \"read\", \"arguments\": {\"file_path\": \"x\"}\n$fence").reason.contains("incomplete"))
        assertTrue(malformed("${fence}tool\n").reason.contains("empty"))
        assertEquals("Text", malformed("Text\n${fence}tool\n\"just a string\"\n$fence").prose)
    }

    @Test fun multipleBlocksAreRejected() {
        val block = "${fence}tool\n{\"name\":\"read\",\"arguments\":{\"file_path\":\"a\"}}\n$fence"
        val result = malformed("First\n$block\nSecond\n$block")
        assertEquals("First", result.prose)
        assertTrue(result.reason.contains("exactly one"))
    }

    @Test fun textAfterTheBlockIsRejected() {
        val result = malformed("${fence}tool\n{\"name\":\"read\",\"arguments\":{}}\n$fence\nLet me know if that helps!")
        assertTrue(result.reason.contains("End the reply"))
    }

    @Test fun shapeIsStrictlyNameAndArguments() {
        listOf(
            "{\"name\":\"read\"}",
            "{\"name\":\"read\",\"arguments\":{},\"id\":\"1\"}",
            "{\"name\":\"read\",\"arguments\":\"{}\"}",
            "{\"name\":\"read file\",\"arguments\":{}}",
            "{\"name\":7,\"arguments\":{}}",
            "[{\"name\":\"read\",\"arguments\":{}}]",
        ).forEach { body -> malformed("${fence}tool\n$body\n$fence") }
    }

    @Test fun nestedBackticksInsideJsonStringsDoNotCloseTheBlock() {
        val markdown = "# Report\n\n${fence}python\nprint('hi')\n$fence\n\nInline `code` and ``more``."
        val arguments = buildJsonObject { put("title", "Code"); put("markdown", markdown) }
        val parsed = call("Creating it.\n${fence}tool\n{\"name\":\"create_pdf\",\"arguments\":$arguments}\n$fence")
        assertEquals(markdown, parsed.arguments["markdown"]!!.jsonPrimitive.content)
    }

    @Test fun rawMultilineStringsWithFenceLinesAreTolerated() {
        // Invalid JSON as written (raw newlines inside a string), but unambiguous: accepted after escaping.
        val parsed = call("${fence}tool\n{\"name\": \"write\", \"arguments\": {\"file_path\": \"a.md\", \"content\": \"line 1\n$fence\nlast\tline\"}}\n$fence")
        assertEquals("line 1\n$fence\nlast\tline", parsed.arguments["content"]!!.jsonPrimitive.content)
    }

    @Test fun jsonFencedCallOfAKnownToolAsksForRepairButOtherJsonStaysPlain() {
        val text = "Calling it:\n${fence}json\n{\"name\": \"create_pdf\", \"arguments\": {\"title\": \"T\", \"markdown\": \"x\"}}\n$fence"
        val repair = malformed(text, setOf("create_pdf"))
        assertEquals("Calling it:", repair.prose)
        assertTrue(repair.reason.contains("```tool"))
        assertTrue(ToolCallProtocol.parse(text, setOf("read")) is ToolCallParse.Plain)
        assertTrue(ToolCallProtocol.parse(text) is ToolCallParse.Plain)
    }

    @Test fun formattedResultIsAFencedJsonBlockThatKeepsStructuredContent() {
        val result = ToolCallProtocol.formatResult("run_python", true, """{"stdout":"4\n","stderr":""}""")
        assertTrue(result.startsWith("${fence}tool_result\n"))
        assertTrue(result.endsWith("\n$fence"))
        val json = Json.parseToJsonElement(result.removePrefix("${fence}tool_result\n").removeSuffix("\n$fence")).jsonObject
        assertEquals("run_python", json["name"]!!.jsonPrimitive.content)
        assertTrue(json["ok"]!!.jsonPrimitive.boolean)
        assertEquals("4\n", json["content"]!!.jsonObject["stdout"]!!.jsonPrimitive.content)
        val failed = Json.parseToJsonElement(ToolCallProtocol.formatResult("read", false, "File missing").lines()[1]).jsonObject
        assertFalse(failed["ok"]!!.jsonPrimitive.boolean)
        assertEquals("File missing", failed["content"]!!.jsonPrimitive.content)
        val long = ToolCallProtocol.formatResult("read", true, "x".repeat(ToolCallProtocol.MAX_RESULT_CHARS + 50))
        assertTrue(long.contains("[Result truncated]"))
        assertEquals(3, long.lines().size)
    }

    @Test fun repairPromptNamesTheProblemAndTheExactForm() {
        val prompt = ToolCallProtocol.repairPrompt("The tool block is not valid JSON.")
        assertTrue(prompt.contains("not valid JSON"))
        assertTrue(prompt.contains("${fence}tool\n{\"name\""))
    }

    @Test fun systemPromptListsToolsWithSignaturesAndSkills() {
        val schema = Json.parseToJsonElement("""{"type":"object","properties":{"code":{"type":"string"},"timeout_s":{"type":"integer"},
            "files":{"type":"array","items":{"type":"object","properties":{"path":{"type":"string"}},"required":["path"]}}},"required":["code"]}""").jsonObject
        val prompt = ToolCallProtocol.systemPrompt(listOf(ToolSpec(function = FunctionSpec("run_python", "Run Python.", schema))),
            listOf("data-analysis" to "CSV statistics and charts"))
        assertTrue(prompt.contains("- run_python(code: string, timeout_s?: integer, files?: array<object{path: string}>): Run Python."))
        assertTrue(prompt.contains("- data-analysis: CSV statistics and charts"))
        assertTrue(prompt.contains("${fence}tool"))
    }

    @Test fun visibleTextHidesTheToolBlockWhileStreaming() {
        val filter = ToolCallProtocol.VisibleText()
        assertEquals("Hello", filter.visible("Hello"))
        assertEquals("Hello\n", filter.visible("Hello\n``"))
        assertEquals("Hello\n", filter.visible("Hello\n```to"))
        assertEquals("Hello\n", filter.visible("Hello\n```tool\n{\"name\""))
        assertEquals("Hello\n", filter.visible("Hello\n```tool\n{\"name\": \"read\"}\n```"))
        val code = ToolCallProtocol.VisibleText()
        assertEquals("Code:\n", code.visible("Code:\n```"))
        assertEquals("Code:\n```py", code.visible("Code:\n```py"))
        assertEquals("Code:\n```python\nx = 1\n", code.visible("Code:\n```python\nx = 1\n"))
    }
}
