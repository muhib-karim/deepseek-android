package app.dsandroid.core.guard

import app.dsandroid.core.tools.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WebGuardPolicyTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun guard(): GuardPolicy = GuardPolicy(
        ToolRegistry(listOf(WebFetchTool(), VisitWebTool(), WebSearchTool())),
        WorkspacePath(temporary.newFolder()),
    )

    @Test fun exactSearchHostAllowsShortQueryFreeUrlsForBothFetchNames() {
        val guard = guard()
        for (name in listOf("web_fetch", "visit_web")) {
            for (url in listOf("https://example.org/article", "https://EXAMPLE.ORG/article", "https://example.org./article")) {
                assertEquals(url, Decision.ALLOW, guard.decide(name, args(url), GuardSettings(), setOf("example.org")))
            }
            assertEquals(Decision.ALLOW, guard.decide(name, args("https://bücher.de/page"), GuardSettings(), setOf("xn--bcher-kva.de")))
            assertEquals(Decision.ALLOW, guard.decide(name, args("https://[2606:4700:4700::1111]/page"), GuardSettings(), setOf("2606:4700:4700::1111")))
            assertEquals(Decision.CONFIRM, guard.decide(name, args("https://sub.example.org/page"), GuardSettings(), setOf("example.org")))
            assertEquals(Decision.CONFIRM, guard.decide(name, args("https://example.org.evil.com/page"), GuardSettings(), setOf("example.org")))
            assertEquals(Decision.CONFIRM, guard.decide(name, args("https://example.org/page"), GuardSettings(), setOf("sub.example.org")))
        }
    }

    @Test fun queryPresenceAndTwoHundredCharacterBoundaryRequireConfirmation() {
        val guard = guard()
        val prefix = "https://example.org/"
        val atLimit = prefix + "a".repeat(200 - prefix.length)
        for (name in listOf("web_fetch", "visit_web")) {
            assertEquals(Decision.ALLOW, guard.decide(name, args(atLimit), GuardSettings(), setOf("example.org")))
            for (url in listOf(atLimit + "a", "https://example.org/?", "https://example.org/?q=private", "https://example.org/?#fragment")) {
                assertEquals(url, Decision.CONFIRM, guard.decide(name, args(url), GuardSettings(), setOf("example.org")))
            }
            assertEquals(Decision.ALLOW, guard.decide(name, args("https://example.org/#?fragment"), GuardSettings(), setOf("example.org")))
        }
    }

    @Test fun noCurrentTurnSearchTrustRequiresConfirmation() {
        val previousTurn = TurnWebContext()
        previousTurn.registerSearchResult(WebSearchResult("Report", "https://example.org/report", ""))
        val nextTurn = TurnWebContext()
        assertEquals(Decision.CONFIRM, guard().decide("web_fetch", args("https://example.org/report"), GuardSettings(), nextTurn.searchHosts))
        assertEquals(Decision.CONFIRM, guard().decide("visit_web", args("https://example.org/report"), GuardSettings()))
    }

    @Test fun invalidOrPrivateUrlsAndMalformedArgumentsAreDenied() {
        val guard = guard()
        for (name in listOf("web_fetch", "visit_web")) {
            for (url in listOf("nonsense", "http://example.org", "https://localhost", "https://10.1.2.3", "https://[::1]", "https://user:pass@example.org",
                " https://example.org/", "https://example.org/\n", "https://example.org\\private")) {
                assertEquals(url, Decision.DENY, guard.decide(name, args(url), GuardSettings(), setOf("example.org", "localhost", "10.1.2.3")))
            }
            for (bad in listOf(JsonObject(emptyMap()), buildJsonObject { put("url", 1) }, buildJsonObject { put("url", JsonNull) },
                buildJsonObject { put("url", "https://example.org"); put("extra", "ignored") })) {
                assertEquals(Decision.DENY, guard.decide(name, bad, GuardSettings(), setOf("example.org")))
            }
        }
    }

    @Test fun searchAlwaysConfirmsAndRejectsInvalidQueries() {
        val guard = guard()
        assertEquals(Risk.CONFIRM, WebSearchTool().risk)
        assertEquals(Decision.CONFIRM, guard.decide("web_search", buildJsonObject { put("query", "my exact private query") }, GuardSettings()))
        assertEquals(Decision.CONFIRM, guard.decide("web_search", buildJsonObject { put("query", "x".repeat(2000)) }, GuardSettings()))
        for (bad in listOf(JsonObject(emptyMap()), buildJsonObject { put("query", 123) }, buildJsonObject { put("query", " ") },
            buildJsonObject { put("query", "x".repeat(2001)) }, buildJsonObject { put("query", "valid"); put("url", "https://example.org") })) {
            assertEquals(Decision.DENY, guard.decide("web_search", bad, GuardSettings()))
        }
    }

    private fun args(url: String) = buildJsonObject { put("url", url) }
}
