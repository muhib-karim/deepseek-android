package app.dsandroid.engine

import android.app.Instrumentation
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import app.dsandroid.MainActivity
import app.dsandroid.core.deepseek.BrowserSignIn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The full build's first screen: Continue with Google (web account) + Use API key (DeepSeek's harness), nothing else. */
@RunWith(AndroidJUnit4::class)
class FirstScreenTest {
    @Before
    fun grant() = V130Shots.grantNotifications()

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private fun awaitWelcome() {
        compose.waitUntil(20_000) { compose.onAllNodesWithTag("welcome-screen", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun showsExactlyTheTwoRoutes() {
        awaitWelcome()
        compose.onNodeWithTag("welcome-google", useUnmergedTree = true).assertIsDisplayed().assertHasClickAction()
        compose.onNodeWithTag("engine-key-field", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("engine-start", useUnmergedTree = true).assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithTag("engine-get-key", useUnmergedTree = true).assertIsDisplayed()
        // The v1.2.0 welcome's extra routes and notes are gone from the full build.
        for (gone in listOf("welcome-browser", "welcome-phone-email", "welcome-paste", "welcome-more", "welcome-api-billing", "welcome-consent")) {
            compose.onNodeWithTag(gone, useUnmergedTree = true).assertDoesNotExist()
        }
        V130Shots.take("01-first-screen")
        compose.onNodeWithTag("engine-key-field", useUnmergedTree = true).performTextInput("bad key")
        compose.onNodeWithTag("engine-start", useUnmergedTree = true).assertIsEnabled().performClick()
        compose.onNodeWithTag("engine-key-error", useUnmergedTree = true).assertIsDisplayed()
    }

    /**
     * Continue with Google keeps the v1.1/v1.2 web-account route: terms consent, the browser guide, then the phone's
     * browser on DeepSeek's sign-in page, whose "Log in with Google" leads to Google's account picker. CI checks the
     * hand-off intent; with -e liveGoogle true (network and a browser on the image) it also waits for Google's picker.
     */
    @Test
    fun continueWithGoogleHandsOffToTheBrowserSignIn() {
        awaitWelcome()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val live = InstrumentationRegistry.getArguments().getString("liveGoogle") == "true"
        val filter = IntentFilter(Intent.ACTION_VIEW).apply { addCategory(Intent.CATEGORY_BROWSABLE); addDataScheme("https") }
        val monitor = Instrumentation.ActivityMonitor(filter, null, !live)
        instrumentation.addMonitor(monitor)
        try {
            compose.onNodeWithTag("welcome-google", useUnmergedTree = true).performClick()
            compose.onNodeWithTag("consent-agree", useUnmergedTree = true).performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("open-browser", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
            V130Shots.take("02-google-route")
            compose.onNodeWithTag("open-browser", useUnmergedTree = true).performClick()
            instrumentation.waitForMonitorWithTimeout(monitor, 10_000)
            assertEquals("the browser was asked to open DeepSeek's sign-in page", 1, monitor.hits)
            assumeTrue("live Google picker check is opt-in", live)
            val device = UiDevice.getInstance(instrumentation)
            val login = device.wait(Until.findObject(By.textContains("Google")), 60_000)
            assertNotNull("DeepSeek's sign-in page offers Google", login)
            login.click()
            assertNotNull("Google's account picker", device.wait(Until.findObject(By.textContains("Choose an account")), 60_000)
                ?: device.wait(Until.findObject(By.textContains("Sign in")), 10_000))
            V130Shots.take("02b-google-picker")
        } finally {
            instrumentation.removeMonitor(monitor)
        }
        assertEquals("https://chat.deepseek.com/sign_in", BrowserSignIn.SIGN_IN_URL)
    }
}
