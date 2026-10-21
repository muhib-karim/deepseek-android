package app.dsandroid.deepseek

import android.app.Activity
import android.app.Application
import android.app.Instrumentation
import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.dsandroid.SmokeActivity
import app.dsandroid.core.deepseek.SessionToken
import app.dsandroid.core.deepseek.WebHistory
import app.dsandroid.data.Appearance
import app.dsandroid.data.SettingsStore
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
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

/** Settings, Account settings, Data controls and Share chat with the real ViewModel and stores; DeepSeek's service is a mock. */
@RunWith(AndroidJUnit4::class)
class SettingsFlowsTest {
    companion object {
        private const val TOKEN = "settings-flows-synthetic-token"
        private const val DELETE = "/api/v0/chat_session/delete"
    }
    @get:Rule val compose = createAndroidComposeRule<SmokeActivity>()
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val server = MockWebServer()
    private val overrides = ConcurrentHashMap<String, ConcurrentLinkedQueue<MockResponse>>()
    private val deletes = ConcurrentLinkedQueue<String>()
    private val sequence = AtomicInteger()
    private val owners = ViewModelStore()
    private lateinit var vm: AccountViewModel
    private lateinit var browser: DeepSeekWebView
    private lateinit var baseUrl: String

    private fun reset() {
        DeepSeekSessionStore(app).clear()
        WebHistory(File(app.filesDir, "deepseek-web-chats.json")).clear()
        app.getSharedPreferences("deepseek_web_appearance", 0).edit().clear().commit()
        app.dsandroid.TestAgentMode.chat(app) // written for the plain chat route; Agent is on by default since v1.2.0
        val settings = SettingsStore(app)
        settings.save(settings.load().copy(fontScale = 1f, collapseThinking = true, audioLanguage = ""))
        runBlocking { DeepSeekCookies.clear() }
    }

    @Before fun setup() {
        reset()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.requestUrl!!.encodedPath
                val body = request.body.readUtf8()
                overrides[path]?.poll()?.let { return it }
                return when (path) {
                    "/api/v0/users/current" -> envelope("""{"id":"user","email":"t***@example.com","mobile_number":"","need_birthday":false}""")
                    "/api/v0/chat_session/create" -> envelope("""{"chat_session":{"id":"remote-${sequence.incrementAndGet()}"}}""")
                    "/api/v0/chat/create_pow_challenge" -> envelope("""{"challenge":{"algorithm":"DeepSeekHashV1","challenge":"abcdef","salt":"salt","difficulty":64,"expire_at":9999999999999,"signature":"signature"}}""")
                    "/api/v0/chat/completion" -> reply("Answer number ${sequence.get()}.")
                    DELETE -> { deletes += Json.parseToJsonElement(body).jsonObject["chat_session_id"]!!.jsonPrimitive.content; envelope("null") }
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
            owners.put("settings", vm)
            browser = DeepSeekWebView(compose.activity, loginUrl = signInUrl, notice = vm::notice)
        }
        compose.waitUntil(15_000) { vm.state.value.ready }
        runBlocking { withContext(Dispatchers.Main) { assertTrue(vm.acceptSession(SessionToken.parse(TOKEN)!!)) } }
        compose.setContent { DeepSeekAccountApp(vm = vm, loginBrowser = browser) }
        compose.waitForIdle()
    }

    @After fun cleanup() {
        if (::vm.isInitialized) compose.runOnUiThread { vm.stop(); owners.clear() }
        if (::browser.isInitialized) compose.runOnUiThread { browser.destroy() }
        reset()
        server.shutdown()
    }

    private fun envelope(data: String) = MockResponse().setHeader("Content-Type", "application/json")
        .setBody("""{"code":0,"data":{"biz_code":0,"biz_data":$data}}""")
    private fun reply(text: String): MockResponse {
        val id = sequence.incrementAndGet() * 2L
        val initial = "data: " + buildJsonObject {
            put("p", ""); put("o", "SET")
            put("v", buildJsonObject {
                put("request_message_id", id - 1); put("response_message_id", id)
                put("response", buildJsonObject { put("fragments", buildJsonArray { add(buildJsonObject { put("type", "RESPONSE"); put("content", text) }) }) })
            })
        } + "\n\n"
        return MockResponse().setHeader("Content-Type", "text/event-stream").setBody(initial + "data: [DONE]\n\n")
    }
    private fun send(prompt: String) {
        compose.onNodeWithTag("account-composer").performTextReplacement(prompt)
        compose.onNodeWithContentDescription("Send message").performClick()
        compose.waitUntil(20_000) { !vm.state.value.busy && vm.state.value.messages.lastOrNull()?.let { !it.user && it.text.isNotEmpty() } == true }
    }
    private fun openSettings() {
        compose.onNodeWithContentDescription("Open navigation").performClick()
        compose.onNodeWithText("Settings").performClick()
        compose.waitUntil(5_000) { vm.state.value.page == AccountPage.SETTINGS }
    }
    /** Intercepts activity starts of [action] (no system UI opens) and keeps the last intent. */
    private class Capture(private val action: String) : Instrumentation.ActivityMonitor() {
        @Volatile var intent: Intent? = null
        override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
            if (intent.action != action) return null
            this.intent = intent
            return Instrumentation.ActivityResult(Activity.RESULT_OK, null)
        }
    }
    private fun <T> capturing(action: String, block: (Capture) -> T): T {
        val capture = Capture(action)
        InstrumentationRegistry.getInstrumentation().addMonitor(capture)
        try { return block(capture) } finally { InstrumentationRegistry.getInstrumentation().removeMonitor(capture) }
    }
    private fun restarted(): AccountViewModel {
        lateinit var next: AccountViewModel
        compose.runOnIdle { next = AccountViewModel(app, baseUrl = baseUrl, powSolver = { 0L }); owners.put("restart-${sequence.incrementAndGet()}", next) }
        compose.waitUntil(15_000) { next.state.value.ready }
        return next
    }

    @Test fun settingsGroupsAndChoicesSurviveARestart() {
        openSettings()
        listOf("Profile", "App", "Audio", "About").forEach { compose.onNodeWithText(it).performScrollTo().assertIsDisplayed() }
        compose.onNodeWithText("AI-generated, for reference only.").performScrollTo().assertIsDisplayed()

        compose.onNodeWithTag("settings-appearance").performScrollTo().performClick()
        compose.onNodeWithTag("choice-DARK").performClick()
        compose.onNodeWithTag("choice-confirm").performClick()
        compose.waitUntil(5_000) { vm.state.value.appearance == Appearance.DARK }

        compose.onNodeWithTag("settings-font").performScrollTo().performClick()
        compose.onNodeWithTag("font-slider").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(1.2f) }
        compose.onNodeWithTag("font-confirm").performClick()
        compose.waitUntil(5_000) { vm.state.value.textScale > 1.15f }

        compose.onNodeWithTag("settings-collapse").performScrollTo().performClick()
        compose.waitUntil(5_000) { !vm.state.value.collapseThinking }

        compose.onNodeWithTag("settings-voice-language").performScrollTo().performClick()
        compose.onNodeWithTag("choice-en-US").performClick()
        compose.onNodeWithTag("choice-confirm").performClick()
        compose.waitUntil(5_000) { vm.state.value.audioLanguage == "en-US" }

        val next = restarted().state.value
        assertEquals(Appearance.DARK, next.appearance)
        assertEquals(1.2f, next.textScale, 0.01f)
        assertFalse(next.collapseThinking)
        assertEquals("en-US", next.audioLanguage)
        captureOfficialScreenshot(compose, true, "settings-after-restart", TOKEN, "mocked-server")
    }

    @Test fun accountSettingsShowWhatDeepSeekReturnsAndLinkOut() {
        openSettings()
        compose.onNodeWithTag("settings-account").performClick()
        compose.waitUntil(5_000) { vm.state.value.page == AccountPage.PROFILE }
        compose.onNodeWithText("t***@example.com").assertIsDisplayed()
        capturing(Intent.ACTION_VIEW) { capture ->
            compose.onNodeWithTag("profile-manage").performClick()
            compose.waitUntil(5_000) { capture.intent != null }
            assertEquals("https://chat.deepseek.com/", capture.intent!!.data.toString())
        }
        // Back returns to Settings, not to the chat.
        compose.onNodeWithContentDescription("Back").performClick()
        compose.waitUntil(5_000) { vm.state.value.page == AccountPage.SETTINGS }
        compose.onNodeWithTag("settings-account").performClick()
        compose.onNodeWithText("Log out", substring = false).performScrollTo().performClick()
        compose.onNodeWithText("Confirm log out?").assertIsDisplayed()
        compose.onAllNodesWithText("Log out", substring = false).onLast().performClick()
        compose.waitUntil(15_000) { !vm.state.value.signedIn && !vm.state.value.busy }
        compose.onNodeWithTag("welcome-screen").assertIsDisplayed()
    }

    @Test fun deleteAllChatsDeletesOnDeepSeekThenHereAndKeepsWhatTheServerRefused() {
        send("First chat")
        compose.onNodeWithContentDescription("New Chat").performClick()
        send("Second chat")
        compose.onNodeWithContentDescription("New Chat").performClick()
        send("Third chat")
        assertEquals(3, vm.state.value.history.size)
        overrides.getOrPut(DELETE) { ConcurrentLinkedQueue() }.add(MockResponse().setResponseCode(500))
        openSettings()
        compose.onNodeWithTag("settings-data").performScrollTo().performClick()
        compose.onNodeWithTag("delete-all-chats").performClick()
        compose.onNodeWithTag("confirm-delete-all").performClick()
        compose.waitUntil(15_000) { !vm.state.value.busy && vm.state.value.history.size == 1 }
        assertEquals("Two chats were deleted on DeepSeek", 2, deletes.size)
        assertEquals("1 chat was not deleted on DeepSeek and stayed. Try again later.", vm.state.value.error)
        assertEquals(1, WebHistory(File(app.filesDir, "deepseek-web-chats.json")).load().size)
        compose.onNodeWithTag("delete-all-chats").performClick()
        compose.onNodeWithTag("confirm-delete-all").performClick()
        compose.waitUntil(15_000) { !vm.state.value.busy && vm.state.value.history.isEmpty() }
        assertTrue(WebHistory(File(app.filesDir, "deepseek-web-chats.json")).load().isEmpty())
    }

    @Test fun shareChatSendsReadableTextAndExportsAPdf() {
        send("What is Kotlin?")
        val text = capturing(Intent.ACTION_CHOOSER) { capture ->
            compose.onNodeWithContentDescription("Share chat").performClick()
            compose.onNodeWithTag("share-chat-text").performClick()
            compose.waitUntil(5_000) { capture.intent != null }
            @Suppress("DEPRECATION") val send = capture.intent!!.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
            assertEquals(Intent.ACTION_SEND, send.action)
            assertEquals("text/plain", send.type)
            send.getStringExtra(Intent.EXTRA_TEXT)!!
        }
        assertTrue(text.contains("**You**") && text.contains("What is Kotlin?") && text.contains("**DeepSeek**") && text.contains("Answer number"))
        assertTrue(text.contains("unofficial client") && text.contains("AI-generated"))
        compose.onNodeWithContentDescription("Share chat").performClick()
        compose.onNodeWithTag("share-chat-pdf").performClick()
        compose.waitUntil(20_000) { vm.state.value.messages.last().artifacts.any { it.fileName.endsWith("-chat.pdf") } }
        val pdf = vm.state.value.messages.last().artifacts.single { it.fileName.endsWith("-chat.pdf") }
        val bytes = File(app.filesDir, "workspace/${pdf.path}").readBytes()
        assertEquals("%PDF", String(bytes.copyOfRange(0, 4), Charsets.US_ASCII))
    }
}
