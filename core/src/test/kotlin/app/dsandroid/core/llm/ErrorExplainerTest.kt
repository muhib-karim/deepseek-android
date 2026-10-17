package app.dsandroid.core.llm

import java.io.IOException
import java.net.SocketTimeoutException
import org.junit.Assert.*
import org.junit.Test

class ErrorExplainerTest {
    @Test fun explainsAuthenticationQuotaAndForbiddenWithoutEchoingBodies() {
        assertEquals("Sign-in expired or key invalid. Sign in again or check your API key.", ErrorExplainer.http(401, ""))
        val quota = "This account has no remaining DeepSeek balance or free quota. Add a DeepSeek API key in Settings, or top up on the DeepSeek platform."
        assertEquals(quota, ErrorExplainer.http(402, ""))
        assertEquals(quota, ErrorExplainer.http(400, "{\"code\":\"insufficient_quota\"}"))
        assertEquals(quota, ErrorExplainer.http(403, "Insufficient Balance"))
        assertEquals(quota, ErrorExplainer.http(400, "{\"code\":\"insufficient_user_quota\"}"))
        assertEquals(quota, ErrorExplainer.http(403, "The account balance is not enough"))
        assertEquals("DeepSeek refused this request for this account (403). This can happen when the account has no access or quota for this app. Try an API key in Settings.", ErrorExplainer.http(403, "private body"))
        assertFalse(ErrorExplainer.http(400, "private body").contains("private body"))
    }
    @Test fun explainsRetryAndNetworkErrors() {
        assertTrue(ErrorExplainer.http(429, "").contains("retry", true))
        assertTrue(ErrorExplainer.http(503, "").contains("busy", true))
        assertTrue(ErrorExplainer.explain(IOException()).contains("connection", true))
        assertTrue(ErrorExplainer.explain(SocketTimeoutException()).contains("timed out", true))
        assertEquals(ErrorExplainer.http(401, ""), ErrorExplainer.explain(ProviderHttpException(401, "")))
    }
}
