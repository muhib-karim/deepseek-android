package app.dsandroid.core.artifacts

import java.util.zip.ZipInputStream
import org.junit.Assert.*
import org.junit.Test

class WebProjectTest {
    private val project = WebProject(files = linkedMapOf("index.html" to "<link href=\"css/site.css\"><script src=\"app.js\"></script>", "css/site.css" to "body{color:red}", "app.js" to "document.title='Ready'"))
    @Test fun zipPreservesIndependentFilesAndPreviewUsesOnlyExactLocalResources() {
        val names = mutableListOf<String>()
        ZipInputStream(project.zip().inputStream()).use { zip ->
            while (true) { val entry = zip.nextEntry ?: break; names += entry.name; zip.closeEntry() }
        }
        assertEquals(listOf("index.html", "css/site.css", "app.js"), names)
        assertTrue(project.preview().contains("https://artifact.invalid/css/site.css"))
        assertEquals("text/css", project.resource("https://artifact.invalid/css/site.css")?.first)
        assertNull(project.resource("https://example.org/css/site.css"))
        assertNull(project.resource("https://artifact.invalid/%2e%2e/private"))
        assertNull(project.resource("https://artifact.invalid:443/app.js"))
        assertNull(project.resource("https://artifact.invalid/missing.js"))
    }
    @Test fun localPreviewPolicyAllowsOnlyTheSyntheticResourceHost() {
        val html = app.dsandroid.core.sandbox.SandboxPreviewDocument.wrap(project.preview(), true, false, localProject = true)
        assertTrue(html.contains("script-src 'unsafe-inline' 'unsafe-eval' https://artifact.invalid;"))
        assertFalse(html.contains(" https:;"))
        listOf("connect-src 'none'", "frame-src 'none'", "worker-src 'none'", "base-uri 'none'").forEach { assertTrue(html.contains(it)) }
    }
    @Test fun traversalMissingEntryAndOversizedContentRejected() {
        listOf(mapOf("../index.html" to "x"), mapOf("index.html" to "x", "a/../../x.js" to "x"), mapOf("a.html" to "x"), mapOf("index.html" to "x".repeat(600_000))).forEach { files ->
            assertThrows(IllegalArgumentException::class.java) { WebProject(files = files).encode() }
        }
    }
    @Test fun stylesheetsResolveRelativeResourcesWithinProject() {
        val p = WebProject(files = mapOf("index.html" to "ok", "css/main.css" to "a{background:url('../art.svg')}", "art.svg" to "<svg/>"))
        assertTrue(p.resource("https://artifact.invalid/css/main.css")!!.second.contains("https://artifact.invalid/art.svg"))
        assertEquals(p, WebProject.decode(p.encode()))
    }
}
