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
        e2e.tap(By.text("Agree"), "Agree to DeepSeek's terms")
        // A Compose test tag does not name the embedded WebView, so the page is found by its class.
        e2e.waitFor(By.clazz("android.webkit.WebView"), "the in-app sign-in page", 30_000)
        // The page (or DeepSeek's security check, or its cookie banner) must show text; a page that stays blank is the failure
        // this guards against. WebView content is not always in the accessibility tree, so the app reports the page's title
        // and text length in the "login-page-state" line. A slow page may first get the app's notice; it has 90 s to render.
        val ready = Pattern.compile("Sign-in page ready: .+ \\((\\d+) characters\\)")
        var state = ""
        e2e.waitUntil("text on DeepSeek's sign-in page", 90_000) {
            state = runCatching { e2e.device.findObject(E2eDriver.res("login-page-state"))?.text }.getOrNull().orEmpty()
            ready.matcher(state).matches() || e2e.device.findObjects(By.clazz("android.webkit.WebView")).any { hasText(it, PAGE_TEXT) }
        }
        e2e.step("in-app-sign-in-page")
        println("E2E login page state: $state")

        e2e.device.pressBack()
        e2e.tap(google, "Continue with Google")
        e2e.tap(By.text("Open DeepSeek in your browser"), "Open DeepSeek in your browser")
        chooseBrowserIfAsked()
        e2e.waitUntil("a browser in front", 30_000) { e2e.device.currentPackageName != Fixture.APP }
        val browser = e2e.device.currentPackageName
        val handlers = e2e.shell("cmd package query-activities --brief -a android.intent.action.VIEW -d https://example.com/ -c android.intent.category.BROWSABLE")
        check(handlers.contains(browser)) { "The app opened $browser, which is not a browser" }
        e2e.waitUntil("chat.deepseek.com in the address bar", 45_000) {
            dismissBrowserFirstRun()
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

    private companion object {
        val PAGE_TEXT: Pattern = Pattern.compile("(?i).*(log ?in|sign ?in|password|email|phone|verify|security).*")
    }

    /** Chrome's first-run screens on a fresh image ("Use without an account", "No thanks") stand between the hand-off and the page. */
    private fun dismissBrowserFirstRun() {
        // Chrome can show several in a row (sign-in, notifications, ...); this runs on every poll of the address-bar wait.
        runCatching {
            (e2e.device.findObject(By.res("com.android.chrome:id/signin_fre_dismiss_button"))
                ?: e2e.device.findObject(By.res("com.android.chrome:id/negative_button"))
                ?: e2e.device.findObject(By.text(Pattern.compile("(?i)(use without an account|no,? thanks)"))))?.click()
        }
    }

    private fun chooseBrowserIfAsked() {
        e2e.device.wait(Until.findObject(By.text(Pattern.compile("Open with.*|Complete action using.*"))), 4_000) ?: return
        e2e.device.findObject(By.text(Pattern.compile("Chrome|Browser|WebView Browser Tester")))?.click()
        e2e.device.findObject(By.text(Pattern.compile("(?i)just once")))?.click()
    }

    /** Walks a WebView's accessibility tree; the tree changes while a page loads, so a stale node means "not found yet". */
    private fun hasText(node: UiObject2, pattern: Pattern): Boolean = runCatching {
        (node.text?.let { pattern.matcher(it).matches() } == true) ||
            (node.contentDescription?.let { pattern.matcher(it).matches() } == true) ||
            node.children.any { hasText(it, pattern) }
    }.getOrDefault(false)
}
