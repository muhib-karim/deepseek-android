package app.dsandroid.core.tools

import app.dsandroid.core.guard.WorkspacePath
import java.io.IOException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WebSourcesTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun searchAndFetchShareStableCitationNumbersWithoutGrantingFetchHostTrust() {
        val web = TurnWebContext()
        val first = web.registerSearchResult(WebSearchResult("First", "https://EXAMPLE.org/report#intro", ""))!!
        val fetched = web.registerFetchedPage("https://example.org/report", "Page title")!!
        val second = web.registerFetchedPage("https://other.org/page", "Other")!!
        assertEquals(1, first.number)
        assertEquals(first.number, fetched.number)
        assertEquals(2, second.number)
        assertEquals(listOf(1, 2), web.sources.map { it.number })
        assertEquals(setOf("example.org"), web.searchHosts)
        assertEquals("example.org", first.domain)
        assertEquals("xn--bcher-kva.de", WebSource(3, "Book", "https://BÜCHER.de/").domain)
    }

    @Test fun restoreRetainsNumbersForSameTurnResumeAndEmptyRestoreClearsTrust() {
        val web = TurnWebContext()
        web.restore(listOf(WebSource(1, "Report", "https://example.org/report")), setOf("EXAMPLE.ORG.", "absent.org"))
        assertEquals(setOf("example.org"), web.searchHosts)
        assertEquals(1, web.registerSearchResult(WebSearchResult("Again", "https://example.org/report", ""))!!.number)
        assertEquals(2, web.registerFetchedPage("https://other.org/", "Other")!!.number)
        web.restore(emptyList(), emptySet())
        assertTrue(web.sources.isEmpty())
        assertTrue(web.searchHosts.isEmpty())
        assertEquals(1, web.registerFetchedPage("https://new.org/", "New")!!.number)
    }

    @Test fun invalidSourcesCannotGrantTrustAndSourceGrowthIsBounded() {
        val web = TurnWebContext()
        assertNull(web.registerSearchResult(WebSearchResult("Private", "https://127.0.0.1/", "")))
        web.restore(listOf(WebSource(1, "Private", "https://10.0.0.1/")), setOf("10.0.0.1"))
        assertTrue(web.sources.isEmpty())
        assertTrue(web.searchHosts.isEmpty())
        repeat(100) { web.registerSearchResult(WebSearchResult("Result $it", "https://site$it.example.org/", "")) }
        assertEquals(64, web.sources.size)
        assertEquals(64, web.searchHosts.size)
        assertEquals(1, web.registerFetchedPage("https://site0.example.org/", "Repeated")!!.number)
    }

    @Test fun successfulSearchAndFetchExposeCitationsAndUntrustedContent() = runTest {
        val ctx = ToolContext(WorkspacePath(temporary.newFolder()))
        val search = WebSearchTool(fetchPage = { url ->
            assertTrue(url.contains("q=two%20words"))
            "<a class='result__a' href='https://example.org/report'>Report &amp; data</a><a class='result__snippet'>Useful facts</a>"
        })
        val searchResult = search.run(buildJsonObject { put("query", "two words") }, ctx)
        assertTrue(searchResult.content.contains("[1] Report & data"))
        assertTrue(searchResult.content.contains("Untrusted"))
        assertEquals(setOf("example.org"), ctx.web.searchHosts)
        val fetch = WebFetchTool(fetchPage = { "<title>Report</title><script>secret()</script><h1>Heading</h1><p>Useful facts.</p>" })
        val fetched = fetch.run(buildJsonObject { put("url", "https://example.org/report") }, ctx)
        assertTrue(fetched.content.contains("[1]"))
        assertTrue(fetched.content.contains("Heading"))
        assertTrue(fetched.content.contains("Useful facts."))
        assertTrue(fetched.content.contains("Untrusted"))
        assertFalse(fetched.content.contains("secret()"))
        assertEquals(1, ctx.web.sources.size)
    }

    @Test fun failedNetworkCallsDoNotRegisterSourcesOrSearchHosts() = runTest {
        val ctx = ToolContext(WorkspacePath(temporary.newFolder()))
        try {
            WebSearchTool(fetchPage = { throw IOException("offline") }).run(buildJsonObject { put("query", "report") }, ctx)
            fail("Expected search failure")
        } catch (_: IOException) { }
        try {
            WebFetchTool(fetchPage = { throw IOException("offline") }).run(buildJsonObject { put("url", "https://example.org/report") }, ctx)
            fail("Expected fetch failure")
        } catch (_: IOException) { }
        assertTrue(ctx.web.sources.isEmpty())
        assertTrue(ctx.web.searchHosts.isEmpty())
    }

    @Test fun customSearchAlsoRegistersOnlyResultHostsAndIgnoresInvalidDestinations() = runTest {
        val ctx = ToolContext(WorkspacePath(temporary.newFolder()))
        val search = WebSearchTool(endpoint = "https://search.example.org", fetchPage = {
            "<article><h3><a href='https://books.example.org/report'>Books</a></h3><p>Books result</p></article>" +
                "<article><h3><a href='https://127.0.0.1/private'>Private</a></h3></article>"
        })
        val result = search.run(buildJsonObject { put("query", "books") }, ctx)
        assertTrue(result.content.contains("[1] Books"))
        assertEquals(listOf("https://books.example.org/report"), ctx.web.sources.map { it.url })
        assertEquals(setOf("books.example.org"), ctx.web.searchHosts)
    }

    @Test fun emptySearchDoesNotGrantTrustToTheSearchEngine() = runTest {
        val ctx = ToolContext(WorkspacePath(temporary.newFolder()))
        val result = WebSearchTool(fetchPage = { "<p>No results</p>" }).run(buildJsonObject { put("query", "missing") }, ctx)
        assertFalse(result.isError)
        assertTrue(ctx.web.sources.isEmpty())
        assertTrue(ctx.web.searchHosts.isEmpty())
    }

    @Test fun fetchValidatesDestinationBeforeTransportAndDoesNotTrustPageLinks() = runTest {
        val ctx = ToolContext(WorkspacePath(temporary.newFolder()))
        val fetch = WebFetchTool(fetchPage = { "<title>Page</title><p>Text</p><a href='https://linked.org/'>Link</a>" })
        try {
            fetch.run(buildJsonObject { put("url", "https://127.0.0.1/") }, ctx)
            fail("Expected destination rejection")
        } catch (_: IllegalArgumentException) { }
        assertTrue(ctx.web.sources.isEmpty())
        fetch.run(buildJsonObject { put("url", "https://example.org/") }, ctx)
        assertEquals(listOf("example.org"), ctx.web.sources.map { it.domain })
        assertTrue(ctx.web.searchHosts.isEmpty())
    }
}
