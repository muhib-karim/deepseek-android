package app.dsandroid.core.deepseek

import java.util.Base64
import org.junit.Assert.*
import org.junit.Test

class SessionTest {
    @Test fun acceptsOnlyTheThreeDocumentedStorageFormats() {
        listOf("synthetic-session", "\"synthetic-session\"", """{"value":"synthetic-session"}""").forEach {
            assertEquals("synthetic-session", SessionToken.parse(it)?.bearer())
        }
        listOf(null, "", "null", "{}", "[]", "true", "123", "{bad", "{\"value\":42}", "bad\r\nheader").forEach {
            assertNull(SessionToken.parse(it))
        }
    }

    @Test fun unwrapsEvaluateJavascriptResultWithoutReadingOtherStorage() {
        assertEquals("synthetic-session", SessionToken.fromJavascript("\"{\\\"value\\\":\\\"synthetic-session\\\"}\"")?.bearer())
        assertNull(SessionToken.fromJavascript("null"))
        assertNull(SessionToken.fromJavascript("{}"))
    }

    @Test fun tokenAndCredentialsNeverPrintTheirSecrets() {
        val token = SessionToken.parse("screenshot-canary-not-a-real-token")!!
        val credentials = WebCredentials(token, "ds_session_id=synthetic-cookie")
        assertFalse(token.toString().contains(token.bearer()))
        assertFalse(credentials.toString().contains(token.bearer()))
        assertFalse(credentials.toString().contains("synthetic-cookie"))
    }

    @Test fun expiryUsesSecondsAndExpiresAtBoundary() {
        val payload = Base64.getUrlEncoder().withoutPadding().encodeToString("{\"exp\":2000}".toByteArray())
        val token = SessionToken.parse("header.$payload.signature")!!
        assertFalse(token.isExpired(1_999_000))
        assertTrue(token.isExpired(2_000_000))
        assertFalse(SessionToken.parse("opaque-token")!!.isExpired(Long.MAX_VALUE))
    }

    @Test fun defaultRoutingNeverRequestsAnApiKey() {
        assertEquals(ChatRoute.LOGIN, sessionRoute(null, false, 0))
        assertEquals(ChatRoute.DEEPSEEK, sessionRoute(SessionToken.parse("opaque-token"), false, 0))
        assertEquals(ChatRoute.API_KEY, sessionRoute(null, true, 0))
        val expired = SessionToken.parse("e.eyJleHAiOjF9.s")
        assertEquals(ChatRoute.LOGIN, sessionRoute(expired, false, 1000))
    }

    @Test fun originCheckRejectsSuffixesUserInfoPortsAndCleartext() {
        assertTrue(DeepSeekOrigin.isTrusted("https://chat.deepseek.com/sign_in?x=1"))
        assertTrue(DeepSeekOrigin.isTrusted("https://chat.deepseek.com:443/"))
        listOf("http://chat.deepseek.com", "https://chat.deepseek.com.evil.test", "https://evil.test@chat.deepseek.com", "https://chat.deepseek.com:444", "file:///chat.deepseek.com", "javascript:void(0)").forEach {
            assertFalse(DeepSeekOrigin.isTrusted(it))
        }
    }
}
