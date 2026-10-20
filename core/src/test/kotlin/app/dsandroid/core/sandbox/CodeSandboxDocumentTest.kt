package app.dsandroid.core.sandbox

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class CodeSandboxDocumentTest {
    @Test fun executablePayloadCannotBreakOutOfItsSingleBootstrapScript() {
        val attack = "</script><img src=https://example.com><script>\"\\\n\u2028\u2029&<"
        val document = CodeSandboxDocument.javascript(attack, "pollKey123")
        assertEquals(1, Regex("</script>", RegexOption.IGNORE_CASE).findAll(document).count())
        assertFalse(document.contains("<img"))
        val encoded = CodeSandboxDocument.javascriptString(attack)
        assertFalse(encoded.contains('<'))
        assertFalse(encoded.contains('\u2028'))
        assertEquals(attack, Json.parseToJsonElement(encoded).jsonPrimitive.content)
    }

    @Test fun thePollFunctionExistsBeforeTheWorkerStarts() {
        val document = CodeSandboxDocument.javascript("return 1", "pollKey123")
        assertTrue(document.indexOf("\"pollKey123\"") < document.indexOf("new Worker("))
        assertTrue(document.contains("The sandbox worker could not start"))
        assertTrue(CodeSandboxDocument.BASE_URL.startsWith("https://") && CodeSandboxDocument.BASE_URL.contains(".invalid/"))
    }

    @Test fun theBootstrapRunsOnlyOnTheSandboxOriginAndOnlyOnce() {
        val bootstrap = CodeSandboxDocument.javascriptPage("return 1", "pollKey123").bootstrap
        assertTrue(CodeSandboxDocument.BASE_URL.startsWith(CodeSandboxDocument.ORIGIN + "/"))
        assertTrue(bootstrap.indexOf("location.origin !== \"" + CodeSandboxDocument.ORIGIN + "\"") < bootstrap.indexOf("new Worker("))
        assertTrue(bootstrap.indexOf("hasOwnProperty.call(window, \"pollKey123\")") < bootstrap.indexOf("new Worker("))
        assertFalse(CodeSandboxDocument.javascriptPage("return 1", "pollKey123").shell.contains("<script"))
    }

    @Test fun rejectsCallerControlledPollExpressions() {
        assertThrows(IllegalArgumentException::class.java) {
            CodeSandboxDocument.javascript("return 1", "x);alert(1);//")
        }
    }

    @Test fun pythonCodeRemainsDataInsideTheWorkerBootstrap() {
        val document = CodeSandboxDocument.python("print('</script><img src=x>')", "pollKey123")
        assertEquals(1, Regex("</script>", RegexOption.IGNORE_CASE).findAll(document).count())
        assertFalse(document.contains("<img"))
        assertTrue(document.length < 20_000)
    }

    @Test fun previewMarkupCannotPrecedeThePolicy() {
        val html = SandboxPreviewDocument.wrap("<!doctype html><html><head><script>1</script></head></html>", true, false)
        assertTrue(html.indexOf("Content-Security-Policy") < html.indexOf("<script>1"))
        assertTrue(SandboxPreviewDocument.wrap("<p>static</p>", false, false).contains("script-src 'none'"))
    }
}
