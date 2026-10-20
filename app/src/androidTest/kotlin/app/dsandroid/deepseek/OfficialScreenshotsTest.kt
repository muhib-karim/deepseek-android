package app.dsandroid.deepseek

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import app.dsandroid.SmokeActivity
import app.dsandroid.core.deepseek.SessionToken
import app.dsandroid.data.Appearance
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** Actual production composables, deterministic fixtures, no WebView or network transport. */
@RunWith(Parameterized::class)
class OfficialScreenshotsTest(private val dark: Boolean) {
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "dark={0}") fun themes() = listOf(arrayOf(false), arrayOf(true))
        private const val CANARY = "screenshot-canary-not-a-real-token"
    }
    @get:Rule val compose = createAndroidComposeRule<SmokeActivity>()
    private val theme get() = if (dark) Appearance.DARK else Appearance.LIGHT
    private val history = listOf(AccountHistory("chat-1", "A small Kotlin example"), AccountHistory("chat-2", "Weekend ideas"))
    private val messages = listOf(
        AccountMessage("u1", true, "Show me a small Kotlin example."),
        AccountMessage("a1", false, "## A simple greeting\n\nUse a function to greet someone.\n\n```kotlin\nfun greet() = \"Hello!\"\n```", "Choose a clear, minimal example."),
    )
    private fun launch(state: AccountUiState, fontScale: Float = 1f, login: () -> Unit = {}, fallback: () -> Unit = {}) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
            AccountTheme(theme) {
                DeepSeekShell(state.copy(appearance = theme), onLogin = login, onApiFallback = fallback,
                    loginContent = { Text("Sign in on chat.deepseek.com", Modifier.fillMaxSize()) })
            }
            }
        }
    }
    @Test fun emptyChat() {
        launch(AccountUiState(ready = true, signedIn = true))
        compose.onNodeWithText("How can I help you?").assertIsDisplayed()
        compose.onNodeWithContentDescription("Open navigation").assertWidthIsAtLeast(48.dp)
        compose.onNodeWithContentDescription("New Chat").assertHeightIsAtLeast(48.dp)
        screenshot("empty-chat")
    }
    @Test fun populatedChat() {
        launch(AccountUiState(ready = true, signedIn = true, title = "A small Kotlin example", messages = messages))
        compose.onNodeWithText("Thinking", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Copy").assertIsDisplayed()
        screenshot("populated-chat")
        compose.onNodeWithTag("thinking-a1").performClick()
        compose.onNodeWithText("Choose a clear, minimal example.").assertIsDisplayed()
    }
    @Test fun drawer() {
        launch(AccountUiState(ready = true, signedIn = true, history = history))
        compose.onNodeWithContentDescription("Open navigation").performClick()
        compose.onNodeWithText("Search chats").assertIsDisplayed()
        compose.onNodeWithText("Weekend ideas").assertIsDisplayed()
        screenshot("drawer")
        compose.onNodeWithTag("history-search").performTextInput("Kotlin")
        compose.onNodeWithText("Weekend ideas").assertDoesNotExist()
    }
    @Test fun settings() {
        launch(AccountUiState(ready = true, signedIn = false, page = AccountPage.SETTINGS))
        compose.onNodeWithText("Log in with DeepSeek").assertIsDisplayed()
        compose.onNodeWithText("API-key fallback").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Unofficial client", substring = true).assertExists()
        screenshot("settings")
    }
    @Test fun loginLoggedOut() {
        launch(AccountUiState(ready = true, page = AccountPage.LOGIN))
        compose.onNodeWithTag("browser-sign-in").assertIsDisplayed()
        compose.onNodeWithText("Continue with Google").assertIsDisplayed()
        screenshot("login-logged-out")
    }
    @Test fun noSessionHasOneTapLoginAndNeverAnApiKeyPrompt() {
        var logins = 0
        launch(AccountUiState(ready = true, signedIn = false), login = { logins++ })
        compose.onNodeWithText("Log in with DeepSeek").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, logins) }
        compose.onAllNodes(hasText("API key", substring = true, ignoreCase = true)).assertCountEquals(0)
        // Signed out, the composer takes no typing and says why.
        compose.onNodeWithTag("account-composer").assertIsNotEnabled()
        compose.onNodeWithText("Log in to chat", useUnmergedTree = true).assertExists()
    }
    @Test fun apiFallbackRequiresAnExplicitSettingsAction() {
        var fallbacks = 0
        launch(AccountUiState(ready = true, page = AccountPage.SETTINGS), fallback = { fallbacks++ })
        compose.runOnIdle { assertEquals(0, fallbacks) }
        compose.onNodeWithText("API-key fallback").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, fallbacks) }
    }
    @Test fun attachmentTrayShowsAllThreeActions() {
        launch(AccountUiState(ready = true, signedIn = true))
        compose.onNodeWithContentDescription("Add attachment").performClick()
        listOf("Camera", "Photo", "Document").forEach { compose.onNodeWithText(it).assertIsDisplayed() }
    }
    @Test fun doubledFontKeepsLoginAndComposerReachable() {
        launch(AccountUiState(ready = true), fontScale = 2f)
        // The signed-out sign-in card scrolls at large font sizes; every route must stay reachable.
        compose.onNodeWithText("Continue with Google").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Log in with DeepSeek").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("Send message").assertIsDisplayed()
        compose.onNodeWithContentDescription("Add attachment").assertIsDisplayed()
    }
    private fun screenshot(name: String) {
        // Exercise real encrypted storage with a synthetic marker; never seed real account data.
        val store = DeepSeekSessionStore(compose.activity)
        try {
            store.save(SessionToken.parse(CANARY)!!)
            assertTrue(store.load() != null)
            captureOfficialScreenshot(compose, dark, name, CANARY)
        } finally { store.clear() }
    }
}
