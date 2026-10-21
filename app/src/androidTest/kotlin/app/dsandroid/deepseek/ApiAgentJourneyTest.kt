package app.dsandroid.deepseek

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
import app.dsandroid.TestAgentMode
import app.dsandroid.core.deepseek.WebHistory
import app.dsandroid.core.llm.ClientIdentity
import app.dsandroid.core.llm.CustomEndpointAuth
import app.dsandroid.core.llm.OpenAiCompatibleClient
import app.dsandroid.data.SettingsStore
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
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
 * v1.2.0 "Agent-first" on a fresh install: the welcome screen's API key button (no "More sign-in options"), the key checked
 * with GET /user/balance, API mode with Agent already on, the examples card, then a native function-calling round trip
 * against a mock OpenAI-compatible API: tools offered, create_pdf streamed as tool_calls in pieces, the tool card with its
 * arguments, the PDF's file card with Share, and the streamed answer. A second journey holds the next model reply to show
 * the step counter and stops the run: the finished tool card stays. Captures feed docs/screenshots.
 */
@RunWith(AndroidJUnit4::class)
class ApiAgentJourneyTest {
    companion object {
        private const val KEY = "synthetic-api-agent-key"
    }

    @get:Rule val compose = createAndroidComposeRule<SmokeActivity>()
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val server = MockWebServer()
    private val completions = ConcurrentLinkedQueue<JsonObject>()
    private val holdAnswer = CountDownLatch(1)
    @Volatile private var holding = false
    private val owners = ViewModelStore()
    private lateinit var vm: AccountViewModel
    private lateinit var browser: DeepSeekWebView
    private fun label(id: Int) = compose.activity.getString(id)

    private fun reset() {
        DeepSeekSessionStore(app).clear()
        WebHistory(File(app.filesDir, "deepseek-web-chats.json")).clear()
        listOf("conversations", "runs").forEach { File(app.filesDir, it).deleteRecursively() }
        app.getSharedPreferences("deepseek_web_appearance", 0).edit().clear().commit()
        TestAgentMode.fresh(app)
        val settings = SettingsStore(app)
        settings.save(settings.load().copy(apiKey = "", accounts = emptyList(), activeAccountId = null, model = ""))
        runBlocking { DeepSeekCookies.clear() }
    }

    private fun sse(vararg chunks: String) = MockResponse().setHeader("Content-Type", "text/event-stream")
        .setBody(chunks.joinToString("") { "data: $it\n\n" } + "data: [DONE]\n\n")
    private fun chunk(delta: JsonObject, finish: String? = null) = buildJsonObject {
        put("id", "v120"); put("object", "chat.completion.chunk"); put("model", "deepseek-chat")
        put("choices", buildJsonArray { add(buildJsonObject { put("index", 0); put("delta", delta); if (finish != null) put("finish_reason", finish) }) })
    }.toString()

    private fun toolCallReply(): MockResponse {
        val arguments = buildJsonObject {
            put("title", "Agent report"); put("markdown", "# Agent report\n\nRevenue grew **12%**."); put("file_path", "report.pdf")
        }.toString()
        val third = arguments.length / 3
        fun part(text: String, first: Boolean = false) = chunk(buildJsonObject {
            put("tool_calls", buildJsonArray { add(buildJsonObject {
                put("index", 0)
                if (first) { put("id", "call_v120"); put("type", "function") }
                put("function", buildJsonObject { if (first) put("name", "create_pdf"); put("arguments", text) })
            }) })
        })
        return sse(part("", first = true), part(arguments.substring(0, third)), part(arguments.substring(third, 2 * third)),
            part(arguments.substring(2 * third)), chunk(buildJsonObject {}, "tool_calls"))
    }

    @Before fun setup() {
        reset()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.requestUrl!!.encodedPath) {
                "/user/balance" -> if (request.getHeader("Authorization") == "Bearer $KEY")
                    MockResponse().setHeader("Content-Type", "application/json")
                        .setBody("""{"is_available":true,"balance_infos":[{"currency":"USD","total_balance":"5.00","granted_balance":"0.00","topped_up_balance":"5.00"}]}""")
                    else MockResponse().setResponseCode(401)
                "/models" -> MockResponse().setHeader("Content-Type", "application/json")
                    .setBody("""{"data":[{"id":"deepseek-chat"},{"id":"deepseek-reasoner"}]}""")
                "/chat/completions" -> {
                    val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
                    completions += body
                    val last = body["messages"]!!.jsonArray.last().jsonObject
                    if (last["role"]!!.jsonPrimitive.content == "tool") {
                        if (holding) holdAnswer.await(60, TimeUnit.SECONDS)
                        sse(chunk(buildJsonObject { put("role", "assistant"); put("content", "Here is your report. ") }),
                            chunk(buildJsonObject { put("content", "The PDF is ready.") }), chunk(buildJsonObject {}, "stop"))
                    } else toolCallReply()
                }
                "/sign_in" -> MockResponse().setHeader("Content-Type", "text/html").setBody("<html><body>Mock sign-in</body></html>")
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()
        val baseUrl = server.url("/").toString()
        compose.runOnUiThread {
            vm = AccountViewModel(app, baseUrl = baseUrl, powSolver = { 0L }, apiBaseUrl = baseUrl)
            owners.put("api-agent", vm)
            browser = DeepSeekWebView(compose.activity, loginUrl = server.url("/sign_in").toString(), notice = vm::notice)
        }
        compose.waitUntil(15_000) { vm.state.value.ready }
        val apiClient = OpenAiCompatibleClient(baseUrl, "deepseek-chat", CustomEndpointAuth(""), ClientIdentity("test", "en", 0))
        compose.setContent { DeepSeekAccountApp(vm = vm, loginBrowser = browser, apiClient = apiClient) }
        compose.waitForIdle()
    }

    @After fun cleanup() {
        holdAnswer.countDown()
        if (::vm.isInitialized) compose.runOnUiThread { vm.stop(); owners.clear() }
        if (::browser.isInitialized) compose.runOnUiThread { browser.destroy() }
        reset()
        server.shutdown()
    }

    private fun capture(name: String) {
        runCatching {
            compose.waitForIdle()
            val directory = File(compose.activity.getExternalFilesDir(null), "smoke-screenshots").apply { mkdirs() }
            val bitmap = compose.onAllNodes(isRoot()).onLast().captureToImage().asAndroidBitmap()
            File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    private fun exists(matcher: SemanticsMatcher) = compose.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    /** Fresh install to API mode through the welcome screen's front-door button; Agent is on without a tap. */
    private fun enterApiModeFromWelcome() {
        compose.waitUntil(15_000) { exists(hasTestTag("welcome-screen")) }
        compose.onNodeWithTag("welcome-api-key").performScrollTo().assertIsDisplayed().assertTextContains("Use your DeepSeek API key (agent with tools)")
        compose.onNodeWithTag("welcome-api-billing").performScrollTo().assertTextContains("billed to your DeepSeek platform balance", substring = true)
        compose.onNodeWithTag("welcome-accounts").assertDoesNotExist()
        capture("v120-welcome-api-key")
        compose.onNodeWithTag("welcome-api-key").performClick()
        compose.onNodeWithTag("welcome-api-key-field").performTextInput(KEY)
        compose.onNodeWithTag("api-key-submit").performClick()
        compose.waitUntil(20_000) { exists(hasTestTag("api-fallback-ui")) }
        compose.waitUntil(15_000) { exists(hasTestTag("agent-examples")) }
        assertEquals(KEY, SettingsStore(app).load().apiKey)
        compose.onNodeWithTag("api-agent-chip").assertIsSelected()
        compose.onNodeWithTag("agent-hint", useUnmergedTree = true).assertExists()
    }

    private fun sendFromComposer(text: String) {
        compose.onNodeWithContentDescription(label(R.string.message_placeholder)).performTextReplacement(text)
        compose.onNodeWithContentDescription(label(R.string.send_message)).performClick()
    }

    @Test fun welcomeApiKeyAgentOnByDefaultToolCardAndFileCard() {
        enterApiModeFromWelcome()
        capture("v120-api-agent-examples")
        // An example fills the composer; nothing is sent until the person sends it.
        compose.onNodeWithTag("agent-example-1").performClick()
        compose.onNodeWithContentDescription(label(R.string.message_placeholder)).assertTextContains("compound interest", substring = true)
        assertTrue(completions.isEmpty())

        sendFromComposer("Make a one-page PDF report of the quarter")
        compose.waitUntil(30_000) { exists(hasTestTag("tool-step-create_pdf")) }
        compose.waitUntil(30_000) { exists(hasText("The PDF is ready.", substring = true)) }
        compose.waitUntil(30_000) { exists(hasContentDescription("Share report.pdf")) }

        val first = completions.first()
        assertEquals("deepseek-chat", first["model"]!!.jsonPrimitive.content)
        assertTrue("Agent on offers the tools", first["tools"]!!.jsonArray.any { it.jsonObject["function"]!!.jsonObject["name"]!!.jsonPrimitive.content == "create_pdf" })
        val second = completions.toList()[1]
        val toolMessage = second["messages"]!!.jsonArray.last().jsonObject
        assertEquals("tool", toolMessage["role"]!!.jsonPrimitive.content)
        assertEquals("call_v120", toolMessage["tool_call_id"]!!.jsonPrimitive.content)

        compose.onNodeWithTag("tool-step-create_pdf").performScrollTo()
        compose.onNodeWithText("Created report.pdf", substring = true, useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("tool-step-details", useUnmergedTree = true).assertDoesNotExist()
        compose.onNode(hasContentDescription("Created report.pdf", substring = true) and hasClickAction()).performClick()
        compose.waitUntil(5_000) { exists(hasTestTag("tool-step-details")) }
        compose.onNodeWithText("file_path: report.pdf", substring = true, useUnmergedTree = true).assertExists()
        capture("v120-api-tool-card")
        compose.onNodeWithContentDescription("Share report.pdf").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("artifact-card-report.pdf", useUnmergedTree = true).assertExists()
        capture("v120-api-file-card")
    }

    @Test fun stepCounterShowsWhileRunningAndStopKeepsTheFinishedStep() {
        enterApiModeFromWelcome()
        holding = true
        sendFromComposer("Make a PDF and then wait")
        // Step 1 called create_pdf; the second model reply is held, so the run is on step 2.
        compose.waitUntil(30_000) { exists(hasTestTag("agent-step-counter") and hasText("Step 2 of up to 8")) }
        compose.waitUntil(10_000) { exists(hasTestTag("tool-step-create_pdf")) }
        capture("v120-api-step-counter")
        compose.onNodeWithContentDescription(label(R.string.stop_generating)).performClick()
        compose.waitUntil(15_000) { !exists(hasTestTag("agent-step-counter")) }
        compose.onNodeWithTag("tool-step-create_pdf").performScrollTo().assertExists()
        compose.onNodeWithText("Created report.pdf", substring = true, useUnmergedTree = true).assertExists()
        holdAnswer.countDown()
    }
}
