package app.dsandroid.deepseek

import android.webkit.CookieManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.dsandroid.core.deepseek.DeepSeekOrigin
import app.dsandroid.core.deepseek.SessionToken
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SessionStorageTest {
    @Test fun logoutClearsOnlyDeepSeekCredentialsAndPreservesOtherHostCookies() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val store = DeepSeekSessionStore(context)
        store.save(SessionToken.parse("synthetic-storage-session")!!)
        assertNotNull(store.load())
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            CookieManager.getInstance().apply {
                setCookie(DeepSeekOrigin.URL, "ds_session_id=synthetic-cookie; Path=/; Secure; HttpOnly")
                setCookie("https://example.org", "unrelated=keep; Path=/; Secure")
                flush()
            }
        }
        store.clear()
        DeepSeekCookies.clear()
        assertNull(store.load())
        assertFalse(DeepSeekCookies.read().contains("synthetic-cookie"))
        assertTrue(CookieManager.getInstance().getCookie("https://example.org").contains("unrelated=keep"))
        CookieManager.getInstance().setCookie("https://example.org", "unrelated=; Max-Age=0; Path=/; Secure")
    }
}
