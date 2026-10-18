package app.dsandroid

import androidx.compose.ui.test.assertIsDisplayed
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
}
