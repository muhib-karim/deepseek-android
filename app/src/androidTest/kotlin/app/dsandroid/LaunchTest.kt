package app.dsandroid

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Fresh emulator opens the welcome screen without model discovery, a WebView load, or API-key prompts. */
@RunWith(AndroidJUnit4::class)
class LaunchTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun launcherActivityShowsWelcome() {
        compose.waitUntil(15_000) { compose.onAllNodesWithText("Log in with phone or email").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("welcome-screen").assertIsDisplayed()
        compose.onNodeWithText("Log in with phone or email").assertIsDisplayed()
        compose.onNodeWithText("API key", substring = true, ignoreCase = true).assertDoesNotExist()
        // Nothing loads DeepSeek's page until the person picks the in-app route.
        compose.onNodeWithTag("login-webview").assertDoesNotExist()
    }

    /** What a person sees on first launch with no session: every sign-in route, and no chat at all (not even a locked one). */
    @Test fun freshLaunchSignedOutShowsSignInAndNoUsableComposer() {
        compose.waitUntil(15_000) { compose.onAllNodesWithText("Continue with Google").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("welcome-screen").assertIsDisplayed()
        compose.onNodeWithText("Sign in to start chatting").assertIsDisplayed()
        compose.onNodeWithText("Sign in with browser").assertIsDisplayed()
        compose.onNodeWithText("Continue with Google").assertIsDisplayed()
        compose.onNodeWithText("Log in with phone or email").assertIsDisplayed()
        compose.onNodeWithText("Paste session").assertIsDisplayed()
        // DeepSeek's terms box, as on the official welcome screen: not ticked on a fresh install.
        compose.onNodeWithTag("welcome-consent").performScrollTo().assertIsOff()
        compose.onNodeWithTag("account-composer").assertDoesNotExist()
        compose.onNodeWithText("Type a message or hold to speak", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText("Think").assertDoesNotExist()
        compose.onNodeWithText("Search").assertDoesNotExist()
        compose.onNodeWithText("Agent").assertDoesNotExist()
        compose.onNodeWithContentDescription("Add attachment").assertDoesNotExist()
        compose.onNodeWithContentDescription("Send message").assertDoesNotExist()
        // No drawer: no history, files, skills or settings while signed out.
        compose.onNodeWithContentDescription("Open navigation").assertDoesNotExist()
        compose.onNodeWithTag("message-list").assertDoesNotExist()
    }
}
