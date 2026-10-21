package app.dsandroid.deepseek

import app.dsandroid.TestAgentMode
import android.app.Application
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.dsandroid.R
import app.dsandroid.SmokeActivity
import app.dsandroid.core.accounts.AccountType
import app.dsandroid.core.deepseek.SessionToken
import app.dsandroid.core.deepseek.WebHistory
import app.dsandroid.core.llm.ClientIdentity
import app.dsandroid.core.llm.CustomEndpointAuth
import app.dsandroid.core.llm.OpenAiCompatibleClient
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

/**
 * Settings > Accounts with the real ViewModel, encrypted stores and both chat clients; DeepSeek's web service, API and
 * balance endpoint are a mock. The rule under test: only a tap in the list changes the account in use.
 */
@RunWith(AndroidJUnit4::class)
class AccountManagerTest {
    companion object {
        private const val TOKEN = "account-manager-synthetic-token"
        private const val COMPLETION = "/api/v0/chat/completion"
        private const val RATE = "DeepSeek is busy or has limited requests. Try again later."
    }

    @get:Rule val compose = createAndroidComposeRule<SmokeActivity>()
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val server = MockWebServer()
    private val overrides = ConcurrentHashMap<String, ConcurrentLinkedQueue<MockResponse>>()
    private data class RequestRecord(val path: String, val authorization: String?)
    private val requests = ConcurrentLinkedQueue<RequestRecord>()
    private val sequence = AtomicInteger()
    private val owners = ViewModelStore()
    private lateinit var vm: AccountViewModel
    private lateinit var browser: DeepSeekWebView

    private fun reset() {
        DeepSeekSessionStore(app).clear()
        WebHistory(File(app.filesDir, "deepseek-web-chats.json")).clear()
        File(app.filesDir, "workbench").deleteRecursively()
        app.getSharedPreferences("deepseek_web_appearance", 0).edit().clear().commit()
        TestAgentMode.chat(app) // written for the plain chat route; Agent is on by default since v1.2.0
        val settings = SettingsStore(app)
        settings.save(settings.load().copy(apiKey = "", accounts = emptyList(), activeAccountId = null))
        runBlocking { DeepSeekCookies.clear() }
    }

    @Before fun setup() {
        reset()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.requestUrl!!.encodedPath
                requests += RequestRecord(path, request.getHeader("Authorization"))
                overrides[path]?.poll()?.let { return it }
                return when (path) {
                    "/api/v0/chat_session/create" -> envelope("""{"chat_session":{"id":"remote-${sequence.incrementAndGet()}"}}""")
                    "/api/v0/users/current" -> envelope("""{"id":"user","email":"t***@example.com","mobile_number":"","need_birthday":false}""")
                    "/api/v0/chat/create_pow_challenge" -> envelope("""{"challenge":{"algorithm":"DeepSeekHashV1","challenge":"abcdef","salt":"salt","difficulty":64,"expire_at":9999999999999,"signature":"signature"}}""")
                    COMPLETION -> reply("Hello there.")
                    "/sign_in" -> MockResponse().setHeader("Content-Type", "text/html").setBody("<html><body>Mock sign-in</body></html>")
                    "/user/balance" -> when (request.getHeader("Authorization")) {
                        "Bearer synthetic-key-one" -> json("""{"is_available":true,"balance_infos":[{"currency":"CNY","total_balance":"110.00","granted_balance":"10.00","topped_up_balance":"100.00"}]}""")
                        "Bearer synthetic-key-two" -> json("""{"is_available":true,"balance_infos":[{"currency":"USD","total_balance":"3.50","granted_balance":"0.00","topped_up_balance":"3.50"}]}""")
                        else -> MockResponse().setResponseCode(401)
                    }
                    "/models" -> json("""{"data":[{"id":"deepseek-chat"}]}""")
                    // The API-key client's mocked quota error: HTTP 402 Insufficient Balance.
                    "/chat/completions" -> json("""{"error":{"message":"Insufficient Balance","type":"unknown_error"}}""").setResponseCode(402)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        val baseUrl = server.url("/").toString()
        val signInUrl = server.url("/sign_in").toString()
        compose.runOnUiThread {
            vm = AccountViewModel(app, baseUrl = baseUrl, powSolver = { 0L }, apiBaseUrl = baseUrl)
            owners.put("accounts", vm)
            browser = DeepSeekWebView(compose.activity, loginUrl = signInUrl, notice = vm::notice)
        }
        compose.waitUntil(15_000) { vm.state.value.ready }
        runBlocking { withContext(Dispatchers.Main) { assertTrue(vm.acceptSession(SessionToken.parse(TOKEN)!!)) } }
        val apiClient = OpenAiCompatibleClient(baseUrl, "deepseek-chat", CustomEndpointAuth(""), ClientIdentity("test", "en", 0))
        compose.setContent { DeepSeekAccountApp(vm = vm, loginBrowser = browser, apiClient = apiClient) }
        compose.waitForIdle()
    }

    @After fun cleanup() {
        if (::vm.isInitialized) compose.runOnUiThread { vm.stop(); owners.clear() }
        if (::browser.isInitialized) compose.runOnUiThread { browser.destroy() }
        reset()
        server.shutdown()
    }

    private fun envelope(data: String) = json("""{"code":0,"data":{"biz_code":0,"biz_data":$data}}""")
    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)

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

    private fun label(id: Int) = compose.activity.getString(id)
    private fun active() = vm.state.value.accounts.single { it.active }

    private fun openAccounts() {
        compose.onNodeWithContentDescription("Open navigation").performClick()
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithTag("manage-accounts").performScrollTo().performClick()
        compose.waitUntil(5_000) { vm.state.value.page == AccountPage.ACCOUNTS }
        compose.onNodeWithText("Your accounts").assertIsDisplayed()
    }

    private fun addProfile(name: String, key: String) {
        val count = vm.state.value.accounts.size
        compose.onNodeWithTag("add-api-key-profile").performScrollTo().performClick()
        compose.onNodeWithTag("profile-label").performTextInput(name)
        compose.onNodeWithTag("profile-key").performTextInput(key)
        compose.onNodeWithTag("save-profile").performClick()
        compose.waitUntil(10_000) { vm.state.value.accounts.size == count + 1 && !vm.state.value.busy }
    }

    private fun addProfileDirectly(name: String, key: String) {
        val count = vm.state.value.accounts.size
        compose.runOnUiThread { vm.addApiKeyProfile(name, key) }
        compose.waitUntil(10_000) { vm.state.value.accounts.size == count + 1 && !vm.state.value.busy }
    }

    private fun capture(name: String) {
        runCatching {
            compose.waitForIdle()
            val directory = File(compose.activity.getExternalFilesDir(null), "smoke-screenshots").apply { mkdirs() }
            val bitmap = compose.onAllNodes(isRoot()).onLast().captureToImage().asAndroidBitmap()
            File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    @Test fun aSecondApiKeyProfileIsListedAndUsedOnlyAfterATap() {
        openAccounts()
        assertEquals(AccountType.DEEPSEEK_WEB, active().type)
        addProfile("Work key", "synthetic-key-one")
        addProfile("Personal key", "synthetic-key-two")
        assertEquals(3, vm.state.value.accounts.size)
        assertEquals("Adding profiles never changes the account in use", AccountType.DEEPSEEK_WEB, active().type)
        assertEquals("", SettingsStore(app).load().apiKey)
        // The keys are stored encrypted: never in the preferences file as plain text.
        val xml = File(app.applicationInfo.dataDir, "shared_prefs/dsandroid_settings.xml").readText()
        assertFalse(xml.contains("synthetic-key-one") || xml.contains("synthetic-key-two"))

        compose.onNodeWithTag("refresh-balances").performScrollTo().performClick()
        compose.waitUntil(15_000) { !vm.state.value.balanceBusy && vm.state.value.accounts.filter { it.type == AccountType.API_KEY }.all { it.lastRead != null } }
        val personal = vm.state.value.accounts.single { it.label == "Personal key" }
        assertEquals("Balance: 3.50 USD", personal.balance)
        assertTrue(personal.lastRead!!.startsWith("Last read today "))
        assertEquals("Balance: 110.00 CNY", vm.state.value.accounts.single { it.label == "Work key" }.balance)
        assertEquals("Balance: not available for web sign-in", active().balance)
        assertEquals(setOf("Bearer synthetic-key-one", "Bearer synthetic-key-two"),
            requests.filter { it.path == "/user/balance" }.mapNotNull { it.authorization }.toSet())
        assertEquals("Reading balances never changes the account in use", AccountType.DEEPSEEK_WEB, active().type)
        // The balance line sits inside the row's merged, clickable semantics.
        compose.onNodeWithTag("account-balance-${personal.id}", useUnmergedTree = true).assertTextEquals("Balance: 3.50 USD")
        capture("accounts-list")

        compose.onNodeWithText("Personal key").performScrollTo().performClick()
        compose.waitUntil(15_000) { active().id == personal.id && !vm.state.value.busy }
        val settings = SettingsStore(app).load()
        assertEquals("synthetic-key-two", settings.apiKey)
        assertEquals(personal.id, settings.activeAccountId)
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("api-fallback-ui").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("API mode · Personal key").assertIsDisplayed()
        assertEquals("The DeepSeek account keeps its own encrypted sign-in", TOKEN, DeepSeekSessionStore(app).load()!!.bearer())
        capture("accounts-api-profile-active")

        compose.onNodeWithTag("api-switch-account").performClick()
        compose.waitUntil(10_000) { vm.state.value.page == AccountPage.ACCOUNTS }
        assertEquals("Opening the list does not switch", personal.id, active().id)
        compose.onNodeWithText("DeepSeek account").performScrollTo().performClick()
        compose.waitUntil(15_000) { active().type == AccountType.DEEPSEEK_WEB && vm.state.value.signedIn && !vm.state.value.busy }
        compose.onNodeWithTag("account-composer").assertIsDisplayed()
    }

    @Test fun aQuotaOrRateErrorShowsSwitchAccountAndKeepsTheActiveAccount() {
        addProfileDirectly("Spare key", "synthetic-key-one")
        val before = active()
        assertEquals(AccountType.DEEPSEEK_WEB, before.type)
        overrides.getOrPut(COMPLETION) { ConcurrentLinkedQueue() }.add(MockResponse().setResponseCode(429))
        compose.onNodeWithTag("account-composer").performTextReplacement("Hello")
        compose.onNodeWithContentDescription("Send message").performClick()

        compose.waitUntil(15_000) { vm.state.value.error == RATE && !vm.state.value.busy }
        compose.onNodeWithTag("account-error").assertTextEquals(RATE)
        compose.onNodeWithTag("switch-account").assertIsDisplayed()
        assertEquals("A rate error never changes the account", before, active())
        assertEquals(TOKEN, DeepSeekSessionStore(app).load()!!.bearer())
        assertEquals("The spare key is not put to use", "", SettingsStore(app).load().apiKey)
        capture("accounts-rate-error")

        compose.onNodeWithTag("switch-account").performClick()
        compose.waitUntil(5_000) { vm.state.value.page == AccountPage.ACCOUNTS }
        compose.onNodeWithText("Your accounts").assertIsDisplayed()
        assertEquals("Switch account only opens the list", before.id, active().id)
        assertEquals("", SettingsStore(app).load().apiKey)
    }

    @Test fun anApiQuotaErrorShowsSwitchAccountAndKeepsTheProfile() {
        addProfileDirectly("Work key", "synthetic-key-one")
        addProfileDirectly("Personal key", "synthetic-key-two")
        val work = vm.state.value.accounts.single { it.label == "Work key" }
        compose.runOnUiThread { vm.switchAccount(work.id) }
        compose.waitUntil(15_000) { active().id == work.id && !vm.state.value.busy }
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("api-fallback-ui").fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(15_000) { requests.any { it.path == "/models" } }
        compose.waitForIdle()

        compose.onNodeWithContentDescription(label(R.string.message_placeholder)).performTextInput("Hello")
        compose.onNodeWithContentDescription(label(R.string.send_message)).performClick()
        compose.waitUntil(20_000) { compose.onAllNodesWithTag("api-error-switch-account").fetchSemanticsNodes().isNotEmpty() }
        assertTrue(requests.any { it.path == "/chat/completions" })
        compose.onAllNodesWithText(label(R.string.error_balance)).onFirst().assertIsDisplayed()
        assertEquals("A quota error never changes the account", work.id, active().id)
        assertEquals("synthetic-key-one", SettingsStore(app).load().apiKey)
        assertEquals(work.id, SettingsStore(app).load().activeAccountId)
        capture("accounts-api-quota-error")

        compose.onNodeWithTag("api-error-switch-account").performClick()
        compose.waitUntil(10_000) { vm.state.value.page == AccountPage.ACCOUNTS }
        compose.onNodeWithText("Your accounts").assertIsDisplayed()
        assertEquals("Switch account only opens the list", work.id, active().id)
        assertEquals("synthetic-key-one", SettingsStore(app).load().apiKey)
        assertEquals(work.id, SettingsStore(app).load().activeAccountId)
    }
}
