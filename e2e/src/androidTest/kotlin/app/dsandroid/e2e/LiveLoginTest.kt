package app.dsandroid.e2e

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import java.util.regex.Pattern
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Live login-page check on an ordinary emulator (no fixture, no routing): DeepSeek's real sign-in page renders inside
 * the release app, and the browser route opens a real browser on chat.deepseek.com. Nothing signs in: there is no test
 * account and Google's own sign-in is never automated. The app must stay signed out throughout.
 */
@RunWith(AndroidJUnit4::class)
class LiveLoginTest {
    private val e2e = E2eDriver("live-login")
    private val phoneEmail = By.text(Pattern.compile("Log in with (DeepSeek|phone or email)"))
    private val google = By.text("Continue with Google")

    @Test fun realSignInPageLoadsAndBrowserHandOffOpensDeepSeek() {
        assumeTrue("Live check only", Fixture.mode == "live")
        e2e.freshStart()
        e2e.waitFor(google, "sign-in routes", 30_000)
        e2e.step("signed-out")

        e2e.tap(phoneEmail, "Log in with phone or email")
        e2e.waitFor(E2eDriver.res("login-webview"), "the in-app sign-in page", 30_000)
        // The page (or DeepSeek's security check in front of it) must show text; a page that stays blank is the failure this
        // guards against. A slow page may first get the app's "isn't showing" notice; it still has to render within 90 s.
        val pageText = Pattern.compile("(?i).*(log ?in|sign ?in|password|email|phone|verify|security).*")
        e2e.waitUntil("text on DeepSeek's sign-in page", 90_000) {
            e2e.device.findObjects(By.clazz("android.webkit.WebView")).any { webView -> hasText(webView, pageText) }
        }
        e2e.step("in-app-sign-in-page")

        e2e.device.pressBack()
        e2e.tap(google, "Continue with Google")
        e2e.tap(By.text("Open DeepSeek in your browser"), "Open DeepSeek in your browser")
        chooseBrowserIfAsked()
        e2e.waitUntil("a browser in front", 30_000) { e2e.device.currentPackageName != Fixture.APP }
        val browser = e2e.device.currentPackageName
        val handlers = e2e.shell("cmd package query-activities --brief -a android.intent.action.VIEW -d https://example.com/ -c android.intent.category.BROWSABLE")
        check(handlers.contains(browser)) { "The app opened $browser, which is not a browser" }
        e2e.waitUntil("chat.deepseek.com in the address bar", 45_000) {
            e2e.device.findObjects(By.textContains("chat.deepseek.com")).isNotEmpty() ||
                e2e.device.findObjects(By.descContains("chat.deepseek.com")).isNotEmpty()
        }
        e2e.step("browser-on-deepseek")

        repeat(4) { if (e2e.device.currentPackageName != Fixture.APP) e2e.device.pressBack() }
        if (e2e.device.currentPackageName != Fixture.APP) e2e.launch()
        e2e.waitFor(google, "still signed out after the browser", 20_000)
        e2e.assertAbsent(E2eDriver.res("account-composer").enabled(true), "a usable composer while signed out")
        e2e.step("back-signed-out")
    }

    private fun chooseBrowserIfAsked() {
        e2e.device.wait(Until.findObject(By.text(Pattern.compile("Open with.*|Complete action using.*"))), 4_000) ?: return
        e2e.device.findObject(By.text(Pattern.compile("Chrome|Browser|WebView Browser Tester")))?.click()
        e2e.device.findObject(By.text(Pattern.compile("(?i)just once")))?.click()
    }

    private fun hasText(node: UiObject2, pattern: Pattern): Boolean =
        (node.text?.let { pattern.matcher(it).matches() } == true) ||
            (node.contentDescription?.let { pattern.matcher(it).matches() } == true) ||
            node.children.any { hasText(it, pattern) }
}
