package app.dsandroid.deepseek

import android.app.Application
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.dsandroid.SmokeActivity
import app.dsandroid.core.deepseek.SessionToken
import app.dsandroid.core.deepseek.WebHistory
import app.dsandroid.core.agent.ToolStatus
import app.dsandroid.core.agent.TurnStep
import java.io.File
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

/** Real ViewModel, production shell, agent loop and tools; only DeepSeek's web service is a mock that replies with tool blocks. */
@RunWith(AndroidJUnit4::class)
class AgentModeTest {
    companion object {
        private const val TOKEN = "agent-mode-synthetic-token"
        private const val COMPLETION = "/api/v0/chat/completion"
        private const val FENCE = "```"
    }

    @get:Rule val compose = createAndroidComposeRule<SmokeActivity>()
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val server = MockWebServer()
    private val completions = ConcurrentLinkedQueue<JsonObject>()
    private val replies = ConcurrentLinkedQueue<String>()
    private val sequence = AtomicInteger()
    private val owners = ViewModelStore()
    private lateinit var vm: AccountViewModel
    private lateinit var browser: DeepSeekWebView

    private fun reset() {
        DeepSeekSessionStore(app).clear()
        WebHistory(File(app.filesDir, "deepseek-web-chats.json")).clear()
        File(app.filesDir, "workspace/account").deleteRecursively()
        app.getSharedPreferences("deepseek_web_appearance", 0).edit().clear().commit()
    }

    @Before fun setup() {
        reset()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.requestUrl!!.encodedPath) {
                "/api/v0/chat_session/create" -> envelope("""{"chat_session":{"id":"agent-session"}}""")
                "/api/v0/users/current" -> envelope("""{"id":"user","email":"t***@example.com","mobile_number":"","need_birthday":false}""")
                "/api/v0/chat/create_pow_challenge" -> envelope("""{"challenge":{"algorithm":"DeepSeekHashV1","challenge":"abcdef","salt":"salt","difficulty":64,"expire_at":9999999999999,"signature":"signature"}}""")
                COMPLETION -> {
                    completions += Json.parseToJsonElement(request.body.readUtf8()).jsonObject
                    reply(replies.poll() ?: "Done.")
                }
                "/sign_in" -> MockResponse().setHeader("Content-Type", "text/html").setBody("<html><body>Mock sign-in</body></html>")
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()
        val baseUrl = server.url("/").toString()
        val signInUrl = server.url("/sign_in").toString()
        compose.runOnUiThread {
            vm = AccountViewModel(app, baseUrl = baseUrl, powSolver = { 0L })
            owners.put("agent", vm)
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

    /** The live service's patch stream: a SET with ids and the first fragment, one APPEND, then [DONE]. */
    private fun reply(text: String): MockResponse {
        val id = sequence.incrementAndGet() * 2L
        val cut = minOf(12, text.length)
        val initial = "data: " + buildJsonObject {
            put("p", ""); put("o", "SET")
            put("v", buildJsonObject {
                put("request_message_id", id - 1); put("response_message_id", id)
                put("response", buildJsonObject { put("fragments", buildJsonArray { add(buildJsonObject { put("type", "RESPONSE"); put("content", text.take(cut)) }) }) })
            })
        } + "\n\n"
        val append = "data: " + buildJsonObject { put("p", "response/fragments/-1/content"); put("o", "APPEND"); put("v", text.drop(cut)) } + "\n\n"
        return MockResponse().setHeader("Content-Type", "text/event-stream").setBody(initial + append + "data: [DONE]\n\n")
    }

    private fun toolBlock(name: String, arguments: JsonObject) = "$FENCE" + "tool\n" + buildJsonObject { put("name", name); put("arguments", arguments) } + "\n$FENCE"

    private fun sendInAgentMode(prompt: String) {
        // Agent is on by default since v1.2.0 (this class clears the preferences first): no tap on the Agent pill.
        compose.waitUntil(5_000) { vm.state.value.agent }
        compose.onNodeWithContentDescription("Agent mode on").assertExists()
        compose.onNodeWithTag("account-composer").performTextReplacement(prompt)
        compose.onNodeWithContentDescription("Send message").performClick()
    }

    private fun capture(name: String) {
        runCatching {
            compose.waitForIdle()
            val directory = File(compose.activity.getExternalFilesDir(null), "smoke-screenshots").apply { mkdirs() }
            val bitmap = compose.onAllNodes(isRoot()).onLast().captureToImage().asAndroidBitmap()
            File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    /** v1.2.0: a fresh install starts with Agent on, the composer says what it can do, and the empty chat offers examples. */
    @Test fun agentIsOnByDefaultWithHintAndExamplesThatFillTheComposer() {
        compose.waitUntil(5_000) { vm.state.value.agent }
        compose.onNodeWithContentDescription("Agent mode on").assertIsDisplayed()
        compose.onNodeWithTag("agent-hint", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("agent-examples").assertIsDisplayed()
        capture("v120-account-agent-default")
        compose.onNodeWithTag("agent-example-3").performClick()
        compose.waitUntil(5_000) { vm.state.value.draft == AGENT_EXAMPLES[3] }
        assertTrue("A tap only fills the composer", completions.isEmpty())
        // The person's own "off" sticks across a restart; nothing else turns it off.
        compose.onNodeWithTag("agent-toggle").performClick()
        compose.waitUntil(5_000) { !vm.state.value.agent }
        assertFalse(app.dsandroid.data.AgentModePrefs.read(app.dsandroid.data.AgentModePrefs.prefs(app), app.dsandroid.data.AgentModePrefs.ACCOUNT))
        compose.onNodeWithTag("agent-examples").assertDoesNotExist()
    }

    @Test fun createPdfToolBlockProducesAPdfAndAnArtifactCard() {
        val markdown = "# Quarterly summary\n\nRevenue grew **12%**.\n\n- North led\n- South recovered\n\n| Region | Revenue |\n|---|---:|\n| North | 1200 |\n| South | 980 |"
        replies += "I'll create the report now.\n" + toolBlock("create_pdf", buildJsonObject {
            put("title", "Quarterly report"); put("markdown", markdown); put("file_path", "report.pdf")
        })
        replies += "Your PDF report is ready."
        sendInAgentMode("Make a PDF report of our quarter")

        compose.waitUntil(45_000) { !vm.state.value.busy && vm.state.value.messages.lastOrNull()?.artifacts?.isNotEmpty() == true }
        assertNull(vm.state.value.error)
        val reply = vm.state.value.messages.last()
        val artifact = reply.artifacts.single()
        assertEquals("report.pdf", artifact.fileName)
        assertEquals("application/pdf", artifact.mimeType)
        val file = File(app.filesDir, "workspace/${artifact.path}")
        assertTrue("PDF file missing at ${artifact.path}", file.isFile && file.length() > 0)
        val pages = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor -> PdfRenderer(descriptor).use { it.pageCount } }
        assertTrue("Expected at least one page", pages >= 1)

        compose.onNodeWithTag("message-list").performScrollToNode(hasTestTag("artifact-card-report.pdf"))
        compose.onNodeWithTag("artifact-card-report.pdf").assertIsDisplayed()
        compose.onNodeWithTag("tool-step-create_pdf").assertExists()
        assertTrue(reply.text.contains("Your PDF report is ready."))
        assertFalse("The raw tool block must not be shown", reply.text.contains(FENCE))
        compose.onAllNodesWithText("${FENCE}tool", substring = true).assertCountEquals(0)
        capture("agent-create-pdf")

        // Both round trips are messages in the same chat: the tool result is the next message after the first reply.
        val bodies = completions.toList()
        assertEquals(2, bodies.size)
        val first = bodies[0]["prompt"]!!.jsonPrimitive.content
        assertTrue(first.startsWith("[Agent mode"))
        assertTrue(first.contains("- create_pdf(title: string, markdown: string, file_path?: string)"))
        assertTrue(first.endsWith("User request:\nMake a PDF report of our quarter"))
        val second = bodies[1]
        val result = second["prompt"]!!.jsonPrimitive.content
        assertTrue(result.startsWith("${FENCE}tool_result\n"))
        assertTrue(result.contains("\"name\":\"create_pdf\"") && result.contains("\"ok\":true"))
        assertEquals("agent-session", second["chat_session_id"]!!.jsonPrimitive.content)
        assertEquals(2L, second["parent_message_id"]!!.jsonPrimitive.long)
        assertFalse(second["search_enabled"]!!.jsonPrimitive.boolean)

        // The card's preview actions open the in-app viewer.
        compose.onNodeWithContentDescription("Open report.pdf").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntil(10_000) { compose.onAllNodesWithText("report.pdf").fetchSemanticsNodes().size > 1 }
    }

    @Test fun networkToolsAskFirstAndADenialIsReportedToTheModel() {
        replies += "Let me read it.\n" + toolBlock("web_fetch", buildJsonObject { put("url", "https://example.com/report") })
        replies += "I could not read that page because access was declined."
        sendInAgentMode("What does https://example.com/report say?")

        compose.waitUntil(30_000) { vm.state.value.confirmation != null }
        assertEquals("web_fetch", vm.state.value.confirmation!!.tool)
        compose.onNodeWithTag("tool-confirmation").assertTextContains("https://example.com/report", substring = true)
        capture("agent-approval")
        compose.onNodeWithText("Deny").performClick()

        compose.waitUntil(30_000) { !vm.state.value.busy && completions.size == 2 }
        val result = completions.toList()[1]["prompt"]!!.jsonPrimitive.content
        assertTrue(result.contains("\"ok\":false") && result.contains("declined"))
        val step = vm.state.value.messages.last().steps.filterIsInstance<TurnStep.ToolCall>().single()
        assertEquals(ToolStatus.DENIED, step.status)
    }
}
