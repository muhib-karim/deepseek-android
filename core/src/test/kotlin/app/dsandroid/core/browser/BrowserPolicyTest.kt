package app.dsandroid.core.browser

import app.dsandroid.core.guard.Decision
import app.dsandroid.core.guard.WorkspacePath
import app.dsandroid.core.tools.ToolContext
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class BrowserPolicyTest {
    private class Driver : BrowserDriver {
        override val url = "https://example.com/page"
        var approved: JsonObject? = null
        override suspend fun preview(action: String, args: JsonObject) = buildJsonObject { put("action", action) }
        override suspend fun execute(action: String, args: JsonObject, approved: JsonObject?): String {
            this.approved = approved
            return "page"
        }
    }
    @Test fun invalidActionsAreDeniedBeforePreview() {
        val driver = Driver()
        listOf(JsonPrimitive(-1), JsonPrimitive(500), JsonPrimitive("1"), JsonPrimitive(true), JsonNull).forEach { target ->
            assertEquals(Decision.DENY, BrowserTool("click", driver).decision(buildJsonObject { put("target", target) }, emptySet()))
        }
        assertEquals(Decision.DENY, BrowserTool("type", driver).decision(buildJsonObject { put("target", 0); put("text", "x".repeat(8001)) }, emptySet()))
        assertEquals(Decision.CONFIRM, BrowserTool("type", driver).decision(buildJsonObject { put("target", 0); put("text", "query") }, emptySet()))
        assertEquals(Decision.DENY, BrowserTool("read_page", driver).decision(buildJsonObject { put("url", "https://other.example/") }, emptySet()))
    }
    @Test fun declinedPreviewCannotCarryIntoANewSafeRead() = runTest {
        val dir = Files.createTempDirectory("browser").toFile()
        try {
            val driver = Driver(); val tool = BrowserTool("read_page", driver)
            val context = ToolContext(WorkspacePath(dir)); val args = buildJsonObject {}
            assertEquals(Decision.CONFIRM, tool.decision(args, emptySet()))
            tool.confirmationArguments(args, context) // User declines; execution never happens.
            assertEquals(Decision.ALLOW, tool.decision(args, setOf("example.com")))
            tool.run(args, context)
            assertNull(driver.approved)
        } finally { dir.deleteRecursively() }
    }
    @Test fun readUsesFetchTrustRules() {
        assertEquals(Decision.ALLOW, BrowserPolicy.read("https://example.com/page", setOf("example.com")))
        assertEquals(Decision.CONFIRM, BrowserPolicy.read("https://example.com/page?", setOf("example.com")))
        assertEquals(Decision.CONFIRM, BrowserPolicy.read("https://other.example/page", setOf("example.com")))
        assertEquals(Decision.DENY, BrowserPolicy.read("https://127.0.0.1/", emptySet()))
        assertEquals(Decision.DENY, BrowserPolicy.read("javascript:alert(1)", emptySet()))
    }
    @Test fun parametersCannotBecomeScript() {
        val text = "');fetch('https://example.com'); //"
        val script = BrowserScripts.inspect(2, text)
        assertTrue(script.contains(JsonPrimitive(text).toString()))
        assertFalse(script.contains("eval("))
        assertTrue(runCatching { BrowserScripts.inspect(-1, "") }.isFailure)
    }
    @Test fun documentBlocksAutomaticActivity() {
        val page = BrowserPolicy.document("<script>fetch('/leak')</script><form action='/send'></form>")
        assertTrue(page.indexOf("Content-Security-Policy") < page.indexOf("<script>"))
        assertTrue(page.contains("script-src 'none'"))
        assertTrue(page.contains("form-action 'none'"))
        assertTrue(page.contains("base-uri 'none'"))
    }
    @Test fun pageMarkerDistinguishesACompletedNavigationFromThePreviousDocument() {
        val page = BrowserPolicy.document("<p>New document</p>", "page-2")
        assertTrue(page.contains("name=\"dsandroid-page\" content=\"page-2\""))
        assertTrue(runCatching { BrowserPolicy.document("", "\"><script>") }.isFailure)
    }
}
