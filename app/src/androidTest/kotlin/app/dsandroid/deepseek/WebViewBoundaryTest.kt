package app.dsandroid.deepseek

import android.content.Context
import android.webkit.CookieManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.dsandroid.core.deepseek.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic HTML loaded locally, never the real website or worker. */
@RunWith(AndroidJUnit4::class)
class WebViewBoundaryTest {
    private suspend fun page(origin: String): DeepSeekWebView = withContext(Dispatchers.Main) {
        val browser = DeepSeekWebView(ApplicationProvider.getApplicationContext<Context>()) { }
        CookieManager.getInstance().setCookie(DeepSeekOrigin.URL, "ds_session_id=synthetic-boundary-cookie; Path=/; Secure")
        browser.view.loadDataWithBaseURL(origin, """<!doctype html><html><body>Offline fixture<script>
            localStorage.setItem('userToken', JSON.stringify({value:'synthetic-boundary-token'}));
            localStorage.setItem('unrelatedSecret', 'must-not-read');
            window.Worker = function() { throw new Error('blocked by fixture'); };
        </script></body></html>""", "text/html", "UTF-8", null)
        browser
    }
    @Test fun exactOriginCaptureAndBlockedWorkerFailWithoutNetwork() = runBlocking {
        val browser = page(DeepSeekOrigin.URL)
        try {
            val captured = withTimeout(10_000) {
                var token: SessionToken? = null
                while (token == null) { delay(100); token = browser.captureSession() }
                checkNotNull(token)
            }
            assertEquals("synthetic-boundary-token", captured.bearer())
            try {
                browser.solve(PowChallenge("DeepSeekHashV1", "abcdef", "salt", 64, 9999999999, "signature"))
                fail("Expected a visible worker error")
            } catch (error: UpdateRequired) { assertTrue(error.message!!.contains("update")) }
        } finally {
            withContext(Dispatchers.Main) { browser.destroy() }
            DeepSeekCookies.clear()
        }
    }
    @Test fun differentOriginCannotYieldASession() = runBlocking {
        val browser = page("https://example.org")
        try {
            delay(500)
            assertNull(browser.captureSession())
        } finally {
            withContext(Dispatchers.Main) { browser.destroy() }
            DeepSeekCookies.clear()
        }
    }
}
