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
import app.dsandroid.core.deepseek.WebCitation
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
        screenshot("thinking-expanded")
    }
    @Test fun thinkingTimeAndSearchSources() {
        val answer = AccountMessage("a2", false, "Kotlin 2.0 is current [1].", "Check the release notes.", thinkingSeconds = 12.4,
            citations = listOf(WebCitation("Kotlin releases", "https://kotlinlang.org/docs/releases.html"), WebCitation("Blog", "https://blog.jetbrains.com/kotlin/")))
        launch(AccountUiState(ready = true, signedIn = true, title = "Kotlin version", messages = listOf(AccountMessage("u2", true, "Latest Kotlin?"), answer)))
        compose.onNodeWithText("Thought for 12 seconds").assertIsDisplayed()
        compose.onNodeWithText("Read 2 web pages").assertIsDisplayed()
        screenshot("search-answer")
        compose.onNodeWithTag("search-sources").performClick()
        compose.onNodeWithText("Kotlin releases").assertIsDisplayed()
        compose.onNodeWithText("kotlinlang.org").assertIsDisplayed()
        screenshot("search-sources")
    }
    @Test fun thinkAndSearchPillsShowTheirState() {
        launch(AccountUiState(ready = true, signedIn = true))
        compose.onNodeWithText("Search", substring = false).assertIsNotSelected().performClick().assertIsSelected()
        compose.onNodeWithText("Think", substring = false).performClick().assertIsSelected()
        screenshot("think-search-on")
    }
    @Test fun composerWithKeyboard() {
        launch(AccountUiState(ready = true, signedIn = true, draft = "A question"))
        compose.onNodeWithTag("account-composer").performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Send message").assertIsDisplayed()
        screenshot("keyboard")
    }
    @Test fun drawer() {
        launch(AccountUiState(ready = true, signedIn = true, history = history))
        compose.onNodeWithContentDescription("Open navigation").performClick()
        compose.onNodeWithText("Search chat content...").assertIsDisplayed()
        compose.onNodeWithText("Weekend ideas").assertIsDisplayed()
        screenshot("drawer")
        compose.onNodeWithTag("history-search").performTextInput("Kotlin")
        compose.onNodeWithText("Weekend ideas").assertDoesNotExist()
    }
    @Test fun settings() {
        launch(AccountUiState(ready = true, signedIn = true, page = AccountPage.SETTINGS))
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
    @Test fun welcomeSignedOut() {
        launch(AccountUiState(ready = true, signedIn = false))
        compose.onNodeWithTag("welcome-screen").assertIsDisplayed()
        compose.onNodeWithText("Sign in with browser").assertIsDisplayed()
        screenshot("welcome")
    }
    @Test fun noSessionHasOneTapLoginAndNeverAnApiKeyPrompt() {
        var logins = 0
        launch(AccountUiState(ready = true, signedIn = false), login = { logins++ })
        compose.onNodeWithText("Log in with phone or email").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, logins) }
        compose.onAllNodes(hasText("API key", substring = true, ignoreCase = true)).assertCountEquals(0)
        // Signed out, there is no chat to type into at all, and no drawer.
        compose.onNodeWithTag("account-composer").assertDoesNotExist()
        compose.onNodeWithContentDescription("Open navigation").assertDoesNotExist()
    }
    @Test fun apiKeyRouteIsBehindMoreOptionsAndNeedsAnExplicitKey() {
        val keys = mutableListOf<String>()
        compose.setContent { AccountTheme(theme) { DeepSeekShell(AccountUiState(ready = true, appearance = theme), onUseApiKey = { keys += it }) } }
        compose.onNodeWithTag("welcome-api-key").assertDoesNotExist()
        compose.onNodeWithTag("welcome-more").performScrollTo().performClick()
        compose.onNodeWithTag("welcome-api-key").performScrollTo().performClick()
        compose.onNodeWithTag("welcome-api-key-field").performTextInput("synthetic-welcome-key")
        compose.onNodeWithText("Check and use").performClick()
        compose.runOnIdle { assertEquals(listOf("synthetic-welcome-key"), keys) }
    }
    @Test fun apiFallbackRequiresAnExplicitSettingsAction() {
        var fallbacks = 0
        launch(AccountUiState(ready = true, signedIn = true, page = AccountPage.SETTINGS), fallback = { fallbacks++ })
        compose.runOnIdle { assertEquals(0, fallbacks) }
        compose.onNodeWithText("API-key fallback").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, fallbacks) }
    }
    @Test fun attachmentTrayShowsAllThreeActions() {
        launch(AccountUiState(ready = true, signedIn = true))
        compose.onNodeWithContentDescription("Add attachment").performClick()
        listOf("Camera", "Photo", "Document").forEach { compose.onNodeWithText(it).assertIsDisplayed() }
        screenshot("attachment-tray")
    }
    @Test fun drawerGroupsChatsByDateAndHidesActionsBehindTheMenu() {
        val now = System.currentTimeMillis()
        launch(AccountUiState(ready = true, signedIn = true, history = listOf(
            AccountHistory("p", "Pinned plan", now - 40L * 86_400_000, pinned = true),
            AccountHistory("t", "Today's chat", now - 60_000),
            AccountHistory("o", "An older chat", now - 3L * 86_400_000))))
        compose.onNodeWithContentDescription("Open navigation").performClick()
        listOf("Pinned", "Today", "7 Days").forEach { compose.onNodeWithText(it).assertIsDisplayed() }
        compose.onNodeWithContentDescription("Rename Today's chat").assertDoesNotExist()
        compose.onNodeWithContentDescription("More options for Today's chat").performClick()
        compose.onNodeWithContentDescription("Rename Today's chat").assertIsDisplayed()
        compose.onNodeWithContentDescription("Pin Today's chat").assertIsDisplayed()
        screenshot("drawer-menu")
    }
    @Test fun doubledFontKeepsLoginAndComposerReachable() {
        launch(AccountUiState(ready = true), fontScale = 2f)
        // The welcome screen scrolls at large font sizes; every route must stay reachable.
        listOf("Sign in with browser", "Continue with Google", "Log in with phone or email", "Paste session").forEach {
            compose.onNodeWithText(it).performScrollTo().assertIsDisplayed()
        }
        compose.onNodeWithTag("account-composer").assertDoesNotExist()
    }
    @Test fun doubledFontKeepsTheSignedInComposerReachable() {
        launch(AccountUiState(ready = true, signedIn = true), fontScale = 2f)
        compose.onNodeWithContentDescription("Dictate message").assertIsDisplayed()
        compose.onNodeWithContentDescription("Add attachment").assertIsDisplayed()
    }
    @Test fun doubledFontShowsSendOnceThereIsText() {
        launch(AccountUiState(ready = true, signedIn = true, draft = "Hello"), fontScale = 2f)
        compose.onNodeWithContentDescription("Send message").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithContentDescription("Dictate message").assertDoesNotExist()
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
