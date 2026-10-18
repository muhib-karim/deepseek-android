package app.dsandroid.core.connectors

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ConnectorTest {
    private val config = ConnectorConfig("one", "Drive", "https://api.example.com/search", "https://api.example.com/files/{id}", "q", "https://auth.example.com/authorize", "https://auth.example.com/token", "public-client", "files.read")
    @Test fun identifiersAndQueriesStayData() {
        val read = config.readUrl("../file?secret=abc")
        assertFalse(read.contains("?secret"))
        assertTrue(read.contains("%2F"))
        assertTrue(config.searchUrl("a&admin=true").contains("a%26admin%3Dtrue"))
        assertTrue(runCatching { config.copy(readEndpoint = "https://evil.example.com/{id}").validate() }.isFailure)
    }
    @Test fun consentIsPerChatAndDisconnectRevokesIt() = runTest {
        val grants = ConnectorGrants()
        var prompts = 0
        repeat(2) { assertTrue(grants.authorize("chat-a", "one") { prompts++; true }) }
        assertTrue(grants.authorize("chat-b", "one") { prompts++; true })
        assertEquals(2, prompts)
        grants.revoke("one")
        assertFalse(grants.authorize("chat-a", "one") { prompts++; false })
        assertEquals(3, prompts)
    }
    @Test fun itemPlaceholderCannotChangeTheCredentialDestination() {
        assertTrue(runCatching { config.copy(searchEndpoint = "https://item.example.com/search", readEndpoint = "https://{id}.example.com/read").validate() }.isFailure)
        assertTrue(runCatching { config.copy(readEndpoint = "https://api.example.com/files/{id}/../admin").validate() }.isFailure)
        assertTrue(runCatching { config.readUrl("..") }.isFailure)
        assertTrue(config.readUrl("a/b").startsWith("https://api.example.com/files/"))
    }
    @Test fun transportReceivesOnlyReadRequestsAndBoundToken() = runTest {
        val calls = mutableListOf<Pair<String, String>>()
        val connector = GenericReadConnector(config, { "fixture-token" }, ConnectorTransport { url, token -> calls += url to token; "{\"files\":[]}" })
        connector.search("report")
        connector.read("file-1")
        assertEquals(2, calls.size)
        assertTrue(calls.all { it.first.startsWith("https://api.example.com/") && it.second == "fixture-token" })
    }
    @Test fun disconnectedConnectorNeverContactsTransport() = runTest {
        var requests = 0
        var token: String? = "fixture-token"
        val connector = GenericReadConnector(config, { token }, ConnectorTransport { _, _ -> requests++; "[]" })
        connector.search("report")
        token = null
        assertTrue(runCatching { connector.read("file-1") }.isFailure)
        assertEquals(1, requests)
    }
    @Test fun deniedConsentAndNewChatRequireAnotherPrompt() = runTest {
        val grants = ConnectorGrants()
        assertFalse(grants.authorize("chat", "one") { false })
        var prompts = 0
        assertTrue(grants.authorize("chat", "one") { prompts++; true })
        grants.clearChat("chat")
        assertTrue(grants.authorize("chat", "one") { prompts++; true })
        assertEquals(2, prompts)
    }
    @Test fun distinctConnectorIdsHaveDistinctToolNames() {
        val first = config.copy(id = "a-b")
        val second = config.copy(id = "ab")
        assertNotEquals(ConnectorTool(first, GenericReadConnector(first, { null }), false).name,
            ConnectorTool(second, GenericReadConnector(second, { null }), false).name)
    }
}
