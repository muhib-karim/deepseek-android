package app.dsandroid.deepseek

import android.Manifest
import android.app.Application
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.webkit.CookieManager
import androidx.compose.ui.test.*
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import app.dsandroid.launch.LaunchRequest
import app.dsandroid.core.launch.QuickAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import app.dsandroid.SmokeActivity
import app.dsandroid.core.deepseek.*
import app.dsandroid.core.llm.OpenAiCompatibleClient
import app.dsandroid.core.llm.CustomEndpointAuth
import app.dsandroid.core.llm.ClientIdentity
import app.dsandroid.R
import app.dsandroid.data.Appearance
import app.dsandroid.data.SettingsStore
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
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
import org.junit.runners.Parameterized

/** Real ViewModel, encrypted stores, production UI and transport; only the remote service/voice are fake. */
@RunWith(Parameterized::class)
class AccountFeaturesTest(private val dark: Boolean) {
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "dark={0}") fun themes() = listOf(arrayOf(false), arrayOf(true))
        private const val TOKEN = "account-feature-synthetic-token"
        private const val EXPIRED = "Your DeepSeek session expired. Please sign in again."
        private const val CHANGED = "DeepSeek's web service changed and this app can't talk to it right now. Try again later, update the app, or switch to API-key mode in Settings."
        private const val COMPLETION = "/api/v0/chat/completion"
        private const val CREATE = "/api/v0/chat_session/create"
        private const val RENAME = "/api/v0/chat_session/update_title"
        private const val DELETE = "/api/v0/chat_session/delete"
    }
    @get:Rule val compose = createAndroidComposeRule<SmokeActivity>()
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val theme get() = if (dark) Appearance.DARK else Appearance.LIGHT
    private val server = MockWebServer()
    private val overrides = ConcurrentHashMap<String, ConcurrentLinkedQueue<MockResponse>>()
    private data class RequestRecord(val path: String, val body: String, val contentType: String?)
    private val requests = ConcurrentLinkedQueue<RequestRecord>()
    private val sequence = AtomicInteger()
    private val owners = ViewModelStore()
    private lateinit var vm: AccountViewModel
    private lateinit var browser: DeepSeekWebView
    private val speech = FakeSpeech()
    private var launched = false
    private val quickLaunch = mutableStateOf<LaunchRequest?>(null)

    @Before fun setup() {
        DeepSeekSessionStore(app).clear()
        WebHistory(File(app.filesDir, "deepseek-web-chats.json")).clear()
        app.getSharedPreferences("deepseek_web_appearance", 0).edit().clear().commit()
        val settings = SettingsStore(app)
        settings.save(settings.load().copy(apiKey = ""))
        runBlocking { DeepSeekCookies.clear() }
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.requestUrl!!.encodedPath
                requests += RequestRecord(path, request.body.readUtf8(), request.getHeader("Content-Type"))
                overrides[path]?.poll()?.let { return it }
                return when (path) {
                    CREATE -> envelope("""{"chat_session":{"id":"remote-${sequence.incrementAndGet()}"}}""")
                    "/api/v0/chat/create_pow_challenge" -> envelope("""{"challenge":{"algorithm":"DeepSeekHashV1","challenge":"abcdef","salt":"salt","difficulty":64,"expire_at":9999999999999,"signature":"signature"}}""")
                    COMPLETION -> reply("Hello", " world.")
                    RENAME, DELETE -> envelope("{}")
                    "/api/v0/file/upload_file" -> envelope("""{"file":{"id":"file-1"}}""")
                    "/models" -> MockResponse().setHeader("Content-Type", "application/json").setBody("""{"data":[{"id":"deepseek-chat"}]}""")
                    "/sign_in" -> MockResponse().setHeader("Content-Type", "text/html").setBody("""<!doctype html><html><head><title>Mock sign-in</title></head><body>Phone or email sign-in<a id="google" href="https://accounts.google.com/signin">Google</a></body></html>""")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        compose.runOnUiThread {
            vm = AccountViewModel(app, baseUrl = server.url("/").toString(), powSolver = { 0L })
            owners.put("account", vm)
            browser = DeepSeekWebView(compose.activity, loginUrl = server.url("/sign_in").toString(), notice = vm::notice)
        }
        compose.waitUntil(15_000) { vm.state.value.ready }
    }

    @After fun cleanup() {
        if (::vm.isInitialized) {
            compose.runOnUiThread { vm.stop(); owners.clear() }
        }
        if (::browser.isInitialized) compose.runOnUiThread { browser.destroy() }
        runBlocking { DeepSeekCookies.clear() }
        DeepSeekSessionStore(app).clear()
        WebHistory(File(app.filesDir, "deepseek-web-chats.json")).clear()
        val settings = SettingsStore(app)
        settings.save(settings.load().copy(apiKey = ""))
        server.shutdown()
    }

    private fun launch(signedIn: Boolean = true) {
        if (signedIn) {
            runBlocking { withContext(Dispatchers.Main) {
                CookieManager.getInstance().setCookie(DeepSeekOrigin.URL, "ds_session_id=synthetic-cookie; Path=/; Secure")
                assertTrue(vm.acceptSession(SessionToken.parse(TOKEN)!!))
            } }
        }
        compose.runOnUiThread { vm.appearance(theme) }
        check(!launched); launched = true
        val apiClient = OpenAiCompatibleClient(server.url("/").toString(), "deepseek-chat", CustomEndpointAuth(""), ClientIdentity("test", "en", 0))
        compose.setContent { DeepSeekAccountApp(vm = vm, loginBrowser = browser, speechInput = speech, apiClient = apiClient,
            launchRequest = quickLaunch.value, launchConsumed = { quickLaunch.value = null }) }
        compose.waitForIdle()
    }
    private fun envelope(data: String) = MockResponse().setHeader("Content-Type", "application/json")
        .setBody("""{"code":0,"data":{"biz_code":0,"biz_data":$data}}""")
    private fun queue(path: String, response: MockResponse) { overrides.getOrPut(path) { ConcurrentLinkedQueue() }.add(response) }
    private fun reply(first: String, rest: String = "", thinking: String = "", stall: Boolean = false): MockResponse {
        val id = sequence.incrementAndGet() * 2L
        val fragments = buildJsonArray {
            if (thinking.isNotEmpty()) add(buildJsonObject { put("type", "THINK"); put("content", thinking) })
            add(buildJsonObject { put("type", "RESPONSE"); put("content", first) })
        }
        val initial = "data: " + buildJsonObject {
            put("p", ""); put("o", "SET"); put("v", buildJsonObject {
                put("request_message_id", id - 1); put("response_message_id", id)
                put("response", buildJsonObject { put("fragments", fragments) })
            })
        } + "\n\n"
        val append = "data: " + buildJsonObject { put("p", "response/fragments/-1/content"); put("o", "APPEND"); put("v", rest) } + "\n\n"
        return MockResponse().setHeader("Content-Type", "text/event-stream").setBody(initial + append + "data: [DONE]\n\n")
            .throttleBody(initial.toByteArray().size.toLong(), if (stall) 5 else 1, TimeUnit.SECONDS)
    }
    private fun send(prompt: String = "Hello", confirm: Boolean = false) {
        compose.onNodeWithTag("account-composer").performTextReplacement(prompt)
        compose.onNodeWithContentDescription("Send message").performClick()
        if (confirm) compose.onNodeWithText("Send", substring = false).performClick()
    }
    private fun finished() {
        compose.waitUntil(15_000) { !vm.state.value.busy && vm.state.value.messages.any { !it.user && it.text.isNotEmpty() } }
        assertNull(vm.state.value.error)
    }
    private fun bodies(path: String) = requests.filter { it.path == path }.map { Json.parseToJsonElement(it.body).jsonObject }
    private fun screenshot(feature: String) = captureOfficialScreenshot(compose, dark, feature, TOKEN, "mocked-server")
    private fun drawer() { compose.onNodeWithContentDescription("Open navigation").performClick() }
    private fun settings() { drawer(); compose.onNodeWithText("Settings").performClick() }
    private fun clipboard(): String = compose.runOnIdle {
        (compose.activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip?.getItemAt(0)?.text?.toString().orEmpty()
    }

    @Test fun feature01_newChat() {
        launch(); send("First chat"); finished()
        compose.onNodeWithContentDescription("New Chat").performClick()
        compose.onNodeWithText("How can I help you?").assertIsDisplayed()
        assertTrue(vm.state.value.messages.isEmpty()); assertEquals("", vm.state.value.draft)
        send("Second chat"); finished()
        assertEquals(2, bodies(CREATE).size)
        assertEquals(2, vm.state.value.history.size)
        screenshot("feature01-new-chat")
    }

    @Test fun feature02_sendStreaming() {
        launch(); queue(COMPLETION, reply("First", " second")); send("Stream please")
        compose.waitUntil(10_000) { vm.state.value.messages.lastOrNull()?.text == "First" }
        assertTrue(vm.state.value.busy)
        compose.onNodeWithText("First").assertIsDisplayed()
        finished(); compose.onNodeWithText("First second").assertIsDisplayed()
        assertEquals("Stream please", bodies(COMPLETION).single()["prompt"]!!.jsonPrimitive.content)
        screenshot("feature02-streaming")
    }

    @Test fun feature03_stop() {
        launch(); queue(COMPLETION, reply("Partial reply", " never delivered", stall = true)); send()
        compose.waitUntil(10_000) { vm.state.value.messages.lastOrNull()?.text == "Partial reply" }
        compose.onNodeWithContentDescription("Stop response").performClick()
        compose.waitUntil(5_000) { !vm.state.value.busy }
        compose.onNodeWithText("Partial reply").assertIsDisplayed()
        compose.onNodeWithTag("account-composer").performTextInput("Continue")
        compose.onNodeWithContentDescription("Send message").assertIsEnabled()
        assertFalse(vm.state.value.requiresNewChat)
        screenshot("feature03-stop")
        send("Continue"); finished()
        assertEquals(1, bodies(CREATE).size)
        assertNotEquals(JsonNull, bodies(COMPLETION).last()["parent_message_id"])
    }

    @Test fun feature04_history() {
        launch(); send("History chat"); finished()
        val id = vm.state.value.history.single().id
        compose.onNodeWithContentDescription("New Chat").performClick()
        drawer(); compose.onNodeWithTag("history-open-$id").performClick()
        compose.onNodeWithText("Hello world.").assertIsDisplayed()
        drawer(); compose.onNodeWithContentDescription("Rename History chat").performClick()
        compose.onNodeWithTag("rename-title").performTextReplacement("Renamed chat")
        compose.onNodeWithText("Save").performClick()
        compose.waitUntil(10_000) { vm.state.value.history.single().title == "Renamed chat" }
        assertEquals("Renamed chat", bodies(RENAME).single()["title"]!!.jsonPrimitive.content)
        assertEquals("Renamed chat", WebHistory(File(app.filesDir, "deepseek-web-chats.json")).load().single().title)
        queue(RENAME, MockResponse().setResponseCode(500))
        compose.onNodeWithContentDescription("Rename Renamed chat").performClick()
        compose.onNodeWithTag("rename-title").performTextReplacement("Should fail")
        compose.onNodeWithText("Save").performClick()
        compose.waitUntil(10_000) { vm.state.value.error != null }
        assertEquals("Renamed chat", vm.state.value.history.single().title)
        queue(DELETE, MockResponse().setResponseCode(500))
        compose.onNodeWithContentDescription("Delete Renamed chat").performClick()
        compose.onNodeWithText("Delete", substring = false).performClick()
        compose.waitUntil(10_000) { bodies(DELETE).size == 1 && !vm.state.value.busy }
        assertEquals(1, vm.state.value.history.size)
        compose.onNodeWithContentDescription("Delete Renamed chat").performClick()
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(1, bodies(DELETE).size)
        screenshot("feature04-history")
        compose.onNodeWithContentDescription("Delete Renamed chat").performClick()
        compose.onNodeWithText("Delete", substring = false).performClick()
        compose.waitUntil(10_000) { vm.state.value.history.isEmpty() }
        assertTrue(WebHistory(File(app.filesDir, "deepseek-web-chats.json")).load().isEmpty())
        assertEquals(bodies(RENAME).first()["chat_session_id"], bodies(DELETE).last()["chat_session_id"])
    }

    @Test fun feature05_deepThink() {
        launch(); queue(COMPLETION, reply("Answer", thinking = "Reason step by step."))
        compose.onNodeWithText("Think", substring = false).performClick(); send(); finished()
        assertTrue(bodies(COMPLETION).single()["thinking_enabled"]!!.jsonPrimitive.boolean)
        compose.onNodeWithText("Reason step by step.").assertDoesNotExist()
        compose.onNodeWithContentDescription("Expand thinking").performClick()
        compose.onNodeWithText("Reason step by step.").assertIsDisplayed()
        screenshot("feature05-thinking")
        compose.onNodeWithContentDescription("Collapse thinking").performClick()
        compose.onNodeWithText("Reason step by step.").assertDoesNotExist()
    }

    @Test fun feature06_search() {
        launch(); compose.onNodeWithText("Search", substring = false).performClick()
        send("Search this", confirm = true); finished()
        assertTrue(bodies(COMPLETION).single()["search_enabled"]!!.jsonPrimitive.boolean)
        screenshot("feature06-search")
    }

    @Test fun feature07_attachment() {
        launch()
        val directory = File(app.cacheDir, "camera").apply { mkdirs() }
        val source = File(directory, "fixture.txt").apply { writeText("Attachment content") }
        val uri = FileProvider.getUriForFile(app, "${app.packageName}.artifacts", source)
        compose.onNodeWithContentDescription("Add attachment").performClick()
        compose.onNodeWithText("Document").assertIsDisplayed()
        // The system picker is outside this app; inject its URI into the real import path.
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.runOnIdle { vm.importAttachment(uri) }
        compose.waitUntil(15_000) { vm.state.value.attachments.isNotEmpty() }
        compose.onNodeWithText("fixture.txt", substring = true).assertIsDisplayed()
        screenshot("feature07-attachment")
        send("Read this", confirm = true); finished()
        val upload = requests.single { it.path == "/api/v0/file/upload_file" }
        assertTrue(upload.contentType!!.startsWith("multipart/form-data"))
        assertTrue(upload.body.contains("Attachment content"))
        assertEquals("file-1", bodies(COMPLETION).single()["ref_file_ids"]!!.jsonArray.single().jsonPrimitive.content)
        source.delete()
        // A blank image must still be attached and uploaded; OCR text is not required.
        compose.onNodeWithContentDescription("New Chat").performClick()
        val photo = File(directory, "blank.png")
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.WHITE)
        photo.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
        compose.runOnIdle { vm.importAttachment(FileProvider.getUriForFile(app, "${app.packageName}.artifacts", photo)) }
        compose.waitUntil(25_000) { vm.state.value.attachments == listOf("blank.png") }
        send("Describe image", confirm = true); finished()
        assertEquals(2, requests.count { it.path == "/api/v0/file/upload_file" })
        assertEquals("file-1", bodies(COMPLETION).last()["ref_file_ids"]!!.jsonArray.single().jsonPrimitive.content)
        val imageUser = vm.state.value.messages.last { it.user }.id
        compose.onNodeWithTag("message-list").performScrollToNode(hasTestTag("edit-$imageUser"))
        compose.onNodeWithTag("edit-$imageUser").performClick()
        compose.onNodeWithTag("edit-message").assertTextContains("Describe image")
        compose.onNodeWithText("Cancel").performClick()
        photo.delete()
    }

    @Test fun feature08_voice() {
        InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(app.packageName, Manifest.permission.RECORD_AUDIO)
        launch()
        compose.onNodeWithContentDescription("Dictate message").assertIsDisplayed().performClick()
        compose.runOnIdle { speech.result?.invoke("Dictated words") }
        compose.onNodeWithTag("account-composer").assertTextEquals("Dictated words")
        screenshot("feature08-voice")
        compose.onNodeWithContentDescription("Dictate message").performClick()
        compose.runOnIdle { speech.failed?.invoke() }
        compose.onNodeWithTag("account-error").assertTextContains("Speech recognition is unavailable", substring = true)
        val starts = speech.starts
        compose.runOnIdle { quickLaunch.value = LaunchRequest(QuickAction.VOICE) }
        compose.waitUntil(10_000) { speech.starts > starts }
        compose.runOnIdle { speech.result?.invoke("Voice shortcut") }
        settings(); compose.onNodeWithText("Back", substring = false).performClick()
        compose.runOnIdle { assertEquals(starts + 1, speech.starts) }
        compose.onNodeWithContentDescription("Dictate message").performClick()
        val oldResult = compose.runOnIdle { checkNotNull(speech.result) }
        compose.onNodeWithContentDescription("New Chat").performClick()
        compose.runOnIdle { oldResult("Stale words") }
        compose.onNodeWithTag("account-composer").assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
    }

    @Test fun feature09_markdownCopy() {
        launch(); queue(COMPLETION, reply("## Example\n\nA **bold** answer.\n\n```kotlin\nval answer = 42\n```"))
        send(); finished()
        compose.onNodeWithText("Example").assertIsDisplayed()
        compose.onNodeWithText("val answer = 42").assertIsDisplayed()
        compose.onNodeWithText("Copy", substring = false).performClick()
        assertEquals("val answer = 42", clipboard().trimEnd())
        screenshot("feature09-markdown")
    }

    @Test fun feature10_messageActions() {
        launch(); send("Original prompt"); finished()
        compose.runOnIdle { vm.rename(vm.state.value.history.single().id, "Keep this title") }
        compose.waitUntil(10_000) { !vm.state.value.busy && vm.state.value.title == "Keep this title" }
        compose.onNodeWithContentDescription("Copy user message").performClick()
        assertEquals("Original prompt", clipboard())
        compose.onNodeWithContentDescription("Copy assistant message").performClick()
        assertEquals("Hello world.", clipboard())
        queue(COMPLETION, reply("Regenerated"))
        compose.onNodeWithContentDescription("Regenerate response").performClick(); finished()
        assertEquals(2, vm.state.value.messages.size)
        assertEquals("Regenerated", vm.state.value.messages.last().text)
        assertEquals("Keep this title", vm.state.value.title)
        assertEquals("Original prompt", bodies(COMPLETION).last()["prompt"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, bodies(COMPLETION).last()["parent_message_id"])
        send("Later turn"); finished()
        val previousParent = bodies(COMPLETION).last()["parent_message_id"]
        assertNotEquals(JsonNull, previousParent)
        queue(COMPLETION, reply("Later regenerated"))
        compose.onNodeWithContentDescription("Regenerate response").performScrollTo().performClick(); finished()
        assertEquals(previousParent, bodies(COMPLETION).last()["parent_message_id"])
        val laterUser = vm.state.value.messages.last { it.user }.id
        compose.onNodeWithTag("message-list").performScrollToNode(hasTestTag("edit-$laterUser"))
        compose.onNodeWithTag("edit-$laterUser").performClick()
        compose.onNodeWithTag("edit-message").performTextReplacement("Edited later turn")
        queue(COMPLETION, reply("Later edited"))
        compose.onNodeWithText("Resend").performClick(); finished()
        assertEquals(4, vm.state.value.messages.size)
        assertEquals(previousParent, bodies(COMPLETION).last()["parent_message_id"])
        val firstUser = vm.state.value.messages.first().id
        compose.onNodeWithTag("message-list").performScrollToNode(hasTestTag("edit-$firstUser"))
        compose.onNodeWithTag("edit-$firstUser").performClick()
        compose.onNodeWithTag("edit-message").performTextReplacement("Edited prompt")
        queue(COMPLETION, reply("Edited answer"))
        compose.onNodeWithText("Resend").performClick(); finished()
        assertEquals(2, vm.state.value.messages.size)
        assertEquals("Edited prompt", vm.state.value.messages.first().text)
        assertEquals("Edited answer", vm.state.value.messages.last().text)
        assertEquals("Keep this title", vm.state.value.title)
        assertEquals(JsonNull, bodies(COMPLETION).last()["parent_message_id"])
        assertEquals("Edited prompt", bodies(COMPLETION).last()["prompt"]!!.jsonPrimitive.content)
        screenshot("feature10-actions")
    }

    @Test fun feature11_settings() {
        launch(); send(); finished(); settings()
        compose.onNodeWithText("System", substring = false).performClick()
        assertEquals(Appearance.SYSTEM, vm.state.value.appearance)
        compose.onNodeWithText(if (dark) "Dark" else "Light", substring = false).performClick()
        assertEquals(theme.name, app.getSharedPreferences("deepseek_web_appearance", 0).getString("theme", null))
        lateinit var reloaded: AccountViewModel
        compose.runOnIdle {
            reloaded = AccountViewModel(app, baseUrl = server.url("/").toString(), powSolver = { 0L })
            owners.put("reloaded", reloaded)
        }
        compose.waitUntil(15_000) { reloaded.state.value.ready }
        assertEquals(theme, reloaded.state.value.appearance)
        compose.onNodeWithTag("fallback-key").performScrollTo().performTextInput("synthetic-api-key")
        compose.onNodeWithText("Save API key").performScrollTo().performClick()
        compose.waitUntil(10_000) { vm.state.value.hasApiKey }
        assertEquals("synthetic-api-key", SettingsStore(app).load().apiKey)
        val xml = File(app.applicationInfo.dataDir, "shared_prefs/dsandroid_settings.xml").readText()
        assertFalse(xml.contains("synthetic-api-key"))
        compose.onNodeWithText("API-key fallback", substring = false).performScrollTo().performClick()
        compose.onNodeWithTag("api-fallback-ui").assertIsDisplayed()
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.message_placeholder)).assertIsDisplayed()
        compose.waitUntil(10_000) { requests.any { it.path == "/models" } }
        // Change the key through the actual API client Settings, then return to account mode.
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.open_navigation)).performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.profile_footer)).performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.account), substring = false).performClick()
        compose.onNode(hasSetTextAction() and hasText(compose.activity.getString(R.string.api_key)))
            .performScrollTo().performTextReplacement("")
        compose.onNodeWithText(compose.activity.getString(R.string.save_settings)).performScrollTo().performClick()
        compose.waitUntil(15_000) { SettingsStore(app).load().apiKey.isEmpty() }
        compose.onNodeWithText("Back to DeepSeek").performClick()
        compose.waitUntil(10_000) { !vm.state.value.hasApiKey }
        compose.onNodeWithText("Clear API key").assertDoesNotExist()
        compose.onNodeWithTag("fallback-key").performScrollTo().performTextInput("synthetic-api-key")
        compose.onNodeWithText("Save API key").performScrollTo().performClick()
        compose.waitUntil(10_000) { vm.state.value.hasApiKey }
        compose.onNodeWithText("Clear API key").performScrollTo().performClick()
        compose.waitUntil(10_000) { !vm.state.value.hasApiKey }
        assertEquals("", SettingsStore(app).load().apiKey)
        screenshot("feature11-settings")
        compose.onNodeWithText("Log out", substring = false).performScrollTo().performClick()
        compose.onAllNodesWithText("Log out", substring = false).onLast().performClick()
        compose.waitUntil(15_000) { !vm.state.value.signedIn && !vm.state.value.busy }
        assertEquals(AccountPage.LOGIN, vm.state.value.page)
        assertNull(DeepSeekSessionStore(app).load())
        assertTrue(WebHistory(File(app.filesDir, "deepseek-web-chats.json")).load().isEmpty())
        assertFalse(DeepSeekCookies.read().contains("synthetic-cookie"))
    }

    @Test fun feature12_loginWebView() {
        launch(signedIn = false)
        compose.onNodeWithText("Log in with DeepSeek").performClick()
        compose.onNodeWithTag("login-webview").assertIsDisplayed()
        compose.waitUntil(15_000) { compose.runOnIdle { browser.view.title == "Mock sign-in" } }
        assertTrue(requests.any { it.path == "/sign_in" })
        compose.runOnIdle { browser.view.evaluateJavascript("document.getElementById('google').click()", null) }
        compose.waitUntil(10_000) { vm.state.value.error == DeepSeekWebView.SSO_NOTICE }
        compose.onNodeWithTag("account-error").assertTextEquals(DeepSeekWebView.SSO_NOTICE)
        assertEquals("Google sign-in isn't allowed inside apps. Use phone number or email on this page.", DeepSeekWebView.SSO_NOTICE)
        compose.runOnIdle { assertFalse(browser.view.url.orEmpty().contains("accounts.google.com")) }
        screenshot("feature12-login")
    }

    @Test fun feature13_sessionExpiry() {
        launch(); queue(CREATE, MockResponse().setResponseCode(401)); send()
        compose.waitUntil(15_000) { vm.state.value.page == AccountPage.LOGIN && !vm.state.value.busy }
        compose.onNodeWithTag("account-error").assertTextEquals(EXPIRED)
        assertNull(DeepSeekSessionStore(app).load()); assertFalse(vm.state.value.signedIn)
        assertTrue(vm.state.value.history.isEmpty())
        assertFalse(DeepSeekCookies.read().contains("synthetic-cookie"))
        screenshot("feature13-expiry")
        listOf(40001, 40002, 40003).forEachIndexed { index, code ->
            runBlocking { withContext(Dispatchers.Main) {
                CookieManager.getInstance().setCookie(DeepSeekOrigin.URL, "ds_session_id=synthetic-cookie; Path=/; Secure")
                assertTrue(vm.acceptSession(SessionToken.parse("synthetic-expiry-$code")!!))
            } }
            val body = if (index == 0) """{"code":$code}""" else """{"code":0,"data":{"biz_code":$code}}"""
            if (index == 2) queue(COMPLETION, MockResponse().setHeader("Content-Type", "text/event-stream").setBody("data: $body\n\n"))
            else queue(CREATE, MockResponse().setHeader("Content-Type", "application/json").setBody(body))
            send("Expired again")
            compose.waitUntil(15_000) { vm.state.value.page == AccountPage.LOGIN && !vm.state.value.busy }
            compose.onNodeWithTag("account-error").assertTextEquals(EXPIRED)
            assertNull(DeepSeekSessionStore(app).load())
            assertTrue(WebHistory(File(app.filesDir, "deepseek-web-chats.json")).load().isEmpty())
        }
    }

    @Test fun feature14_endpointChange() {
        launch(); queue(CREATE, MockResponse().setResponseCode(404)); send()
        compose.waitUntil(10_000) { vm.state.value.error == CHANGED && !vm.state.value.busy }
        compose.onNodeWithTag("account-error").assertTextEquals(CHANGED)
        assertTrue(vm.state.value.signedIn)
        screenshot("feature14-endpoint-change")
        settings(); compose.onNodeWithText("API-key fallback", substring = false).performScrollTo().assertIsEnabled()
    }

    private class FakeSpeech : AccountSpeechInput {
        var starts = 0
        var result: ((String) -> Unit)? = null
        var failed: (() -> Unit)? = null
        override fun listen(result: (String) -> Unit, failed: () -> Unit) { starts++; this.result = result; this.failed = failed }
        override fun cancel() { result = null; failed = null }
    }
}
