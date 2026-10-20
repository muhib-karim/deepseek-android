package app.dsandroid.core.sandbox

import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class CodeResultParserTest {
    @Test fun decodesWebViewStringEnvelopeAndPreservesNullReturn() {
        val payload = """{"stdout":"hello\n","stderr":"","value":"null","hasValue":true,"failed":false,"truncated":false}"""
        val result = requireNotNull(CodeResultParser.parse(JsonPrimitive(payload).toString()))
        assertEquals("hello\n", result.stdout)
        assertEquals("null", result.value)
        assertFalse(result.failed)
        assertNull(CodeResultParser.parse("null"))
    }

    @Test fun aRunningPageIsNeitherAResultNorAFailure() {
        assertTrue(CodeResultParser.isRunning("\"\""))
        assertNull(CodeResultParser.parse("\"\""))
        assertFalse(CodeResultParser.isRunning("null"))
        assertNull(CodeResultParser.parse("null"))
    }

    @Test fun malformedOrOversizedResultsFailClosed() {
        for (value in listOf("{}", "\"not json\"", "x".repeat(700_000),
            JsonPrimitive("""{"stdout":1,"stderr":"","hasValue":false,"failed":false}""").toString())) {
            assertTrue(requireNotNull(CodeResultParser.parse(value)).failed)
        }
    }

    @Test fun boundsOutputEvenWhenJavaScriptReturnsMore() {
        val payload = """{"stdout":"${"a".repeat(20_000)}","stderr":"b","hasValue":false,"failed":false}"""
        val result = requireNotNull(CodeResultParser.parse(JsonPrimitive(payload).toString()))
        assertEquals(16_000, result.stdout.length)
        assertEquals("", result.stderr)
        assertTrue(result.truncated)
    }
}
