package app.dsandroid.deepseek

import android.app.Application
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.dsandroid.SmokeActivity
import app.dsandroid.attachments.SharedInput
import app.dsandroid.core.deepseek.SessionToken
import app.dsandroid.core.deepseek.WebHistory
import app.dsandroid.core.launch.QuickAction
import app.dsandroid.launch.LaunchRequest
import java.io.File
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The sign-in gate with the real ViewModel, encrypted stores and production UI; only DeepSeek's service is a mock.
 * Nothing unlocks without DeepSeek confirming the session, and every way into the app waits behind the welcome screen.
 */
@RunWith(AndroidJUnit4::class)
class SignInGateTest {
    companion object {
        private const val TOKEN = "sign-in-gate-synthetic-token"
        private const val CURRENT = "/api/v0/users/current"
        private const val VALID = """{"code":0,"data":{"biz_code":0,"biz_data":{"id":"user","email":"t***@example.com","mobile_number":"","need_birthday":false}}}"""
    }
    @get:Rule val compose = createAndroidComposeRule<SmokeActivity>()
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val server = MockWebServer()
    private val overrides = ConcurrentHashMap<String, ConcurrentLinkedQueue<MockResponse>>()
    private val paths = ConcurrentLinkedQueue<String>()
    private val owners = ViewModelStore()
    private lateinit var vm: AccountViewModel
    private lateinit var browser: DeepSeekWebView
    private lateinit var baseUrl: String
    private val shared = mutableStateOf<SharedInput?>(null)
    private val launchRequest = mutableStateOf<LaunchRequest?>(null)

    private fun reset() {
        DeepSeekSessionStore(app).clear()
        WebHistory(File(app.filesDir, "deepseek-web-chats.json")).clear()
        BrowserSessionInbox.take(); BrowserSessionInbox.ignored.value = false; BrowserSessionInbox.clearStarted(app)
        runBlocking { DeepSeekCookies.clear() }
    }

    @Before fun setup() {
        reset()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.requestUrl!!.encodedPath
                paths += path
                overrides[path]?.poll()?.let { return it }
                return when (path) {
                    CURRENT -> json(VALID)
                    "/sign_in" -> MockResponse().setHeader("Content-Type", "text/html").setBody("<html><body>Mock sign-in</body></html>")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        baseUrl = server.url("/").toString()
        val signInUrl = server.url("/sign_in").toString()
        compose.runOnUiThread {
            vm = AccountViewModel(app, baseUrl = baseUrl, powSolver = { 0L })
            owners.put("gate", vm)
            browser = DeepSeekWebView(compose.activity, loginUrl = signInUrl, notice = vm::notice)
        }
        compose.waitUntil(15_000) { vm.state.value.ready }
        compose.setContent {
            DeepSeekAccountApp(vm = vm, loginBrowser = browser, sharedInput = shared.value, sharedConsumed = { shared.value = null },
                launchRequest = launchRequest.value, launchConsumed = { launchRequest.value = null })
        }
        compose.waitForIdle()
    }

    @After fun cleanup() {
        if (::vm.isInitialized) compose.runOnUiThread { vm.stop(); owners.clear() }
        if (::browser.isInitialized) compose.runOnUiThread { browser.destroy() }
        reset()
        server.shutdown()
    }

    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
    private fun queue(path: String, response: MockResponse) { overrides.getOrPut(path) { ConcurrentLinkedQueue() }.add(response) }
    private fun offer(token: String): SessionCheck = runBlocking { withContext(Dispatchers.Main) { vm.signIn(SessionToken.parse(token)!!) } }
    private fun assertLocked() {
        assertFalse(vm.state.value.signedIn)
        assertNull(DeepSeekSessionStore(app).load())
        compose.onNodeWithTag("welcome-screen").assertIsDisplayed()
        compose.onNodeWithTag("account-composer").assertDoesNotExist()
        compose.onNodeWithContentDescription("Open navigation").assertDoesNotExist()
    }

    @Test fun aRejectedSessionStaysLockedAndIsNotOfferedAgain() {
        queue(CURRENT, MockResponse().setResponseCode(401))
        assertEquals(SessionCheck.REJECTED, offer(TOKEN))
        assertLocked()
        compose.onNodeWithTag("account-error").assertTextContains("did not accept", substring = true)
        val requests = paths.count { it == CURRENT }
        assertEquals("The same rejected session is refused without asking again", SessionCheck.REJECTED, offer(TOKEN))
        assertEquals(requests, paths.count { it == CURRENT })
        queue(CURRENT, json("""{"code":40003,"msg":"invalid token","data":null}"""))
        assertEquals(SessionCheck.REJECTED, offer("$TOKEN-second"))
        assertLocked()
    }

    @Test fun anUnconfirmedSessionStaysLocked() {
        queue(CURRENT, MockResponse().setResponseCode(500))
        assertEquals(SessionCheck.UNVERIFIED, offer(TOKEN))
        assertLocked()
        compose.onNodeWithTag("account-error").assertTextContains("could not be checked", substring = true)
        queue(CURRENT, json("not json"))
        assertEquals(SessionCheck.UNVERIFIED, offer(TOKEN))
        assertLocked()
        // Once DeepSeek answers, the same session works: an unconfirmed one is not remembered as rejected.
        assertEquals(SessionCheck.SIGNED_IN, offer(TOKEN))
        compose.onNodeWithTag("account-composer").assertIsDisplayed()
    }

    @Test fun anExpiredSessionIsRefusedWithoutAsking() {
        val payload = Base64.getUrlEncoder().withoutPadding().encodeToString("""{"exp":1000}""".toByteArray())
        assertEquals(SessionCheck.REJECTED, offer("header.$payload.signature"))
        assertLocked()
        assertTrue(paths.none { it == CURRENT })
    }

    @Test fun aNewAccountThatOwesItsBirthDateStaysLocked() {
        queue(CURRENT, json("""{"code":0,"data":{"biz_code":0,"biz_data":{"id":"user","need_birthday":true}}}"""))
        assertEquals(SessionCheck.NEEDS_BIRTHDAY, offer(TOKEN))
        assertLocked()
    }

    @Test fun aConfirmedSessionUnlocksAndLogoutLocksAgain() {
        assertEquals(SessionCheck.SIGNED_IN, offer(TOKEN))
        compose.onNodeWithTag("account-composer").assertIsDisplayed()
        assertEquals("t***@example.com", vm.state.value.profileEmail)
        compose.runOnIdle { vm.logout() }
        compose.waitUntil(15_000) { !vm.state.value.signedIn && !vm.state.value.busy }
        assertLocked()
        assertNull(vm.state.value.profileEmail)
    }

    @Test fun pagesAndActionsStayClosedWhileSignedOut() {
        compose.runOnIdle {
            vm.navigate(AccountPage.SETTINGS); vm.navigate(AccountPage.SKILLS)
        }
        assertEquals(AccountPage.CHAT, vm.state.value.page)
        compose.runOnIdle { vm.showChatFiles(); vm.runCode("x", "print(1)"); vm.exportPdf("x") }
        compose.waitForIdle()
        assertNull(vm.state.value.chatFiles)
        assertEquals("Sign in to DeepSeek first.", vm.state.value.error)
        assertLocked()
        // The sign-in page and the account list stay reachable, and back returns to the welcome screen.
        compose.onNodeWithText("Log in with phone or email").performClick()
        compose.onNodeWithTag("login-webview").assertIsDisplayed()
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        assertLocked()
    }

    @Test fun sharedContentWaitsForSignIn() {
        compose.runOnIdle { shared.value = SharedInput("Shared from another app", emptyList(), false) }
        compose.waitForIdle()
        compose.onNodeWithTag("welcome-held-share").assertIsDisplayed()
        assertNotNull("Shared content is held, not used or dropped", shared.value)
        assertEquals("", vm.state.value.draft)
        assertLocked()
        assertEquals(SessionCheck.SIGNED_IN, offer(TOKEN))
        compose.waitUntil(10_000) { shared.value == null }
        assertEquals("Shared from another app", vm.state.value.draft)
    }

    @Test fun shortcutsWaitForSignIn() {
        compose.runOnIdle { launchRequest.value = LaunchRequest(QuickAction.NEW_CHAT) }
        compose.waitForIdle()
        assertNotNull("A shortcut is held until sign-in", launchRequest.value)
        assertLocked()
        assertEquals(SessionCheck.SIGNED_IN, offer(TOKEN))
        compose.waitUntil(10_000) { launchRequest.value == null }
        compose.onNodeWithTag("account-composer").assertIsDisplayed()
    }

    @Test fun anUnrequestedBrowserLinkIsIgnoredWithANotice() {
        compose.runOnIdle { BrowserSessionInbox.ignored.value = true }
        compose.waitUntil(5_000) { vm.state.value.error?.contains("didn't ask for") == true }
        assertLocked()
    }

    @Test fun aRevokedSessionLocksTheAppOnTheNextStart() {
        assertEquals(SessionCheck.SIGNED_IN, offer(TOKEN))
        queue(CURRENT, MockResponse().setResponseCode(401))
        lateinit var restarted: AccountViewModel
        compose.runOnIdle {
            restarted = AccountViewModel(app, baseUrl = baseUrl, powSolver = { 0L })
            owners.put("restarted", restarted)
        }
        compose.waitUntil(15_000) { restarted.state.value.ready }
        compose.waitUntil(15_000) { !restarted.state.value.signedIn && restarted.state.value.error != null }
        assertNull(DeepSeekSessionStore(app).load())
        assertEquals(AccountPage.CHAT, restarted.state.value.page)
    }

    @Test fun aStoredSessionSurvivesAnOfflineStart() {
        assertEquals(SessionCheck.SIGNED_IN, offer(TOKEN))
        queue(CURRENT, MockResponse().setResponseCode(503))
        lateinit var restarted: AccountViewModel
        compose.runOnIdle {
            restarted = AccountViewModel(app, baseUrl = baseUrl, powSolver = { 0L })
            owners.put("offline", restarted)
        }
        compose.waitUntil(15_000) { restarted.state.value.ready }
        compose.waitUntil(10_000) { paths.count { it == CURRENT } >= 2 }
        compose.waitForIdle()
        assertTrue("Service trouble is not a rejection", restarted.state.value.signedIn)
        assertNotNull(DeepSeekSessionStore(app).load())
    }
}
