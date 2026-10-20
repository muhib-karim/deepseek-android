package app.dsandroid.core.accounts

import app.dsandroid.core.llm.ClientIdentity
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test

class BalanceTest {
    private val documented = """{"is_available": true, "balance_infos": [{"currency": "CNY", "total_balance": "110.00", "granted_balance": "10.00", "topped_up_balance": "100.00"}]}"""
    private val identity = ClientIdentity("0.7.0", "en", 0)

    @Test fun parsesTheDocumentedResponse() {
        val reading = BalanceParser.parse(documented, readAt = 42)
        assertEquals(true, reading.available)
        assertEquals(listOf(CurrencyBalance("CNY", "110.00", "10.00", "100.00")), reading.balances)
        assertEquals(42L, reading.readAt)
        assertNull(reading.error)
        val two = BalanceParser.parse("""{"is_available": false, "balance_infos": [{"currency": "USD", "total_balance": "0.00"}, {"currency": "CNY", "total_balance": "3.5"}]}""", 1)
        assertEquals(false, two.available)
        assertEquals(listOf("USD", "CNY"), two.balances.map { it.currency })
        assertEquals("", two.balances.first().granted)
    }

    @Test fun rejectsUnexpectedShapes() {
        listOf("[]", "{}", """{"balance_infos": "x"}""", """{"balance_infos": [{"total_balance": "1"}]}""", "not json",
            """{"balance_infos": [{"currency": "<script>", "total_balance": "1"}]}""").forEach {
            assertTrue(it, runCatching { BalanceParser.parse(it, 0) }.isFailure)
        }
    }

    @Test fun clientSendsTheKeyOnlyToTheBalanceEndpoint() { runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(documented))
            server.start()
            val reading = DeepSeekBalanceClient(server.url("/").toString(), identity).read("synthetic-balance-key") { 1234L }
            val request = server.takeRequest()
            assertEquals("GET", request.method)
            assertEquals("/user/balance", request.path)
            assertEquals("Bearer synthetic-balance-key", request.getHeader("Authorization"))
            assertEquals("DeepSeek-Android-Unofficial/0.7.0 (Android)", request.getHeader("User-Agent"))
            assertEquals("110.00", reading.balances.single().total)
            assertEquals(1234L, reading.readAt)
        }
    } }

    @Test fun failuresBecomePlainReadingsWithTheirTime() { runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"message":"Authentication Fails"}}"""))
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("{\"surprise\": true}"))
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
            server.start()
            val client = DeepSeekBalanceClient(server.url("/").toString(), identity)
            val refused = client.read("synthetic-balance-key") { 7L }
            assertEquals(7L, refused.readAt)
            assertTrue(refused.error!!.contains("check your API key"))
            assertTrue(refused.balances.isEmpty())
            assertEquals("DeepSeek returned a balance this app could not read.", client.read("synthetic-balance-key") { 8L }.error)
            assertTrue(client.read("synthetic-balance-key") { 9L }.error!!.startsWith("The connection was interrupted"))
        }
    } }

    @Test fun onlyHttpsOrLoopbackBaseUrlsAreAccepted() {
        assertTrue(runCatching { DeepSeekBalanceClient("http://api.example.com", identity) }.isFailure)
        assertTrue(runCatching { DeepSeekBalanceClient("https://user:pass@api.deepseek.com", identity) }.isFailure)
        assertTrue(runCatching { DeepSeekBalanceClient("https://api.deepseek.com", identity) }.isSuccess)
        assertTrue(runCatching { DeepSeekBalanceClient("http://127.0.0.1:8080/", identity) }.isSuccess)
        assertTrue(runCatching { runBlocking { DeepSeekBalanceClient("https://api.deepseek.com", identity).read("bad key with spaces") } }.isFailure)
    }

    @Test fun summariesSayWhatWasReadAndWhen() {
        val zone = ZoneOffset.UTC
        val now = 1_760_960_700_000L // 2025-10-20 11:45 UTC
        assertEquals("Balance: not available for web sign-in", BalanceText.summary(AccountType.DEEPSEEK_WEB, null))
        assertEquals("Balance: not read yet", BalanceText.summary(AccountType.API_KEY, null))
        val reading = BalanceReading(readAt = now - 60_000, available = true, balances = listOf(CurrencyBalance("CNY", "110.00", "10.00", "100.00"), CurrencyBalance("USD", "2.00")))
        assertEquals("Balance: 110.00 CNY, 2.00 USD", BalanceText.summary(AccountType.API_KEY, reading))
        assertEquals("Balance: 0.00 USD (not enough for API calls)",
            BalanceText.summary(AccountType.API_KEY, BalanceReading(readAt = now, available = false, balances = listOf(CurrencyBalance("USD", "0.00")))))
        assertEquals("Balance: could not be read. Too many requests.", BalanceText.summary(AccountType.API_KEY, BalanceReading(readAt = now, error = "Too many requests.")))
        assertEquals("Last read today 11:44", BalanceText.readAt(reading, now, zone))
        assertEquals("Last read 2025-10-18 09:05", BalanceText.readAt(BalanceReading(readAt = 1_760_778_300_000L), now, zone))
        assertNull(BalanceText.readAt(null, now, zone))
    }
}
