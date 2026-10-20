package app.dsandroid.deepseek

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.webkit.CookieManager
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.dsandroid.SmokeActivity
import app.dsandroid.core.deepseek.DeepSeekOrigin
import java.io.File
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opt-in diagnostic against the real chat.deepseek.com sign-in page (the emulator workflow's live_login input).
 * It records page addresses without queries, console lines, cookie NAMES and a screenshot; it never signs in.
 */
@RunWith(AndroidJUnit4::class)
class LiveLoginDiagnosticTest {
    private val pageState = """(() => ({path: location.pathname, title: document.title,
        challenge: !!document.getElementById('challenge-container'),
        textLength: document.body ? document.body.innerText.trim().length : 0,
        inputs: document.querySelectorAll('input').length}))()"""

    @Test fun realSignInPagePassesTheSecurityCheckAndRenders() {
        assumeTrue("Opt-in live check", InstrumentationRegistry.getArguments().getString("liveLogin") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = ApplicationProvider.getApplicationContext<Context>()
        val out = File(app.getExternalFilesDir(null), "live-login").apply { deleteRecursively(); mkdirs() }
        val notices = Collections.synchronizedList(mutableListOf<String>())
        val states = mutableListOf<String>()
        ActivityScenario.launch(SmokeActivity::class.java).use { scenario ->
            lateinit var browser: DeepSeekWebView
            scenario.onActivity { activity ->
                browser = DeepSeekWebView(activity) { notices += it }
                activity.setContentView(browser.view)
                browser.openLogin()
            }
            var last = JSONObject()
            val end = SystemClock.uptimeMillis() + 75_000
            while (SystemClock.uptimeMillis() < end) {
                Thread.sleep(3_000)
                val latch = CountDownLatch(1)
                var raw = "null"
                scenario.onActivity { browser.view.evaluateJavascript(pageState) { raw = it ?: "null"; latch.countDown() } }
                latch.await(5, TimeUnit.SECONDS)
                states += raw
                last = runCatching { JSONObject(raw) }.getOrDefault(JSONObject())
                if (!last.optBoolean("challenge", true) && last.optInt("inputs", 0) > 0) break
            }
            Thread.sleep(2_000)
            instrumentation.uiAutomation.takeScreenshot()?.let { shot ->
                File(out, "sign_in.png").outputStream().use { shot.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
            val cookieNames = CookieManager.getInstance().getCookie(DeepSeekOrigin.URL).orEmpty()
                .split(';').map { it.substringBefore('=').trim() }.filter { it.isNotEmpty() }
            File(out, "trace.txt").writeText(buildString {
                appendLine("cookie names: $cookieNames")
                appendLine("notices: ${notices.toList()}")
                appendLine("page states:"); states.forEach { appendLine("  $it") }
                appendLine("trace:"); browser.diagnostics().forEach { appendLine("  $it") }
            })
            scenario.onActivity { browser.destroy() }
            assertFalse("Still on the security check: $last", last.optBoolean("challenge", true))
            assertTrue("The sign-in form did not render: $last", last.optInt("inputs", 0) > 0)
        }
    }
}
