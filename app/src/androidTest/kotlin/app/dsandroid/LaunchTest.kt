package app.dsandroid

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Fresh emulator has no credentials: model discovery rejects locally without making a request. */
@RunWith(AndroidJUnit4::class)
class LaunchTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun launcherActivityShowsChat() {
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.open_navigation)).assertIsDisplayed()
    }
}
