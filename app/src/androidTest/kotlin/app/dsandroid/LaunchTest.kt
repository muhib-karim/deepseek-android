package app.dsandroid

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Fresh emulator opens the account shell without model discovery, a WebView load, or API-key prompts. */
@RunWith(AndroidJUnit4::class)
class LaunchTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun launcherActivityShowsChat() {
        compose.onNodeWithContentDescription("Open navigation").assertIsDisplayed()
        compose.waitUntil(15_000) { compose.onAllNodesWithText("Log in with DeepSeek").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Log in with DeepSeek").assertIsDisplayed()
        compose.onNodeWithText("API key", substring = true, ignoreCase = true).assertDoesNotExist()
    }

    /** What a person sees on first launch with no session: every sign-in route on the main screen, and no usable chat. */
    @Test fun freshLaunchSignedOutShowsSignInAndNoUsableComposer() {
        compose.waitUntil(15_000) { compose.onAllNodesWithText("Continue with Google").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("signin-card").assertIsDisplayed()
        compose.onNodeWithText("Sign in to start chatting").assertIsDisplayed()
        compose.onNodeWithText("Continue with Google").assertIsDisplayed()
        compose.onNodeWithText("Sign in with browser").assertIsDisplayed()
        compose.onNodeWithText("Paste session").assertIsDisplayed()
        compose.onNodeWithTag("account-composer").assertIsNotEnabled()
        compose.onNodeWithText("Log in to chat", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Think").assertIsNotEnabled()
        compose.onNodeWithText("Search").assertIsNotEnabled()
        compose.onNodeWithText("Agent").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Add attachment").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Send message").assertIsNotEnabled()
    }
}
