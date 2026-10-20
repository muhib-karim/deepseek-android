package app.dsandroid.deepseek

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.dsandroid.SmokeActivity
import app.dsandroid.core.agent.ToolStatus
import app.dsandroid.core.agent.TurnStep
import app.dsandroid.core.deepseek.SessionToken
import app.dsandroid.core.deepseek.WebHistory
import app.dsandroid.core.mcp.RemoteServer
import app.dsandroid.core.mcp.RemoteTool
import app.dsandroid.core.mcp.RemoteToolDefinition
import app.dsandroid.data.SettingsStore
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

/**
 * Plugins & tools with the real ViewModel, settings store, shell and agent loop; DeepSeek's web service is a mock that
 * records every prompt (the tool list the model is offered) and replies with tool blocks.
 */
@RunWith(AndroidJUnit4::class)
class PluginsToolsTest {
    companion object {
        private const val TOKEN = "plugins-tools-synthetic-token"
        private const val COMPLETION = "/api/v0/chat/completion"
        private const val FENCE = "```"
        private val SERVER = RemoteServer("fixture-server", "Fixture tools", "https://mcp.example.com/mcp", listOf(RemoteToolDefinition("lookup",
            "Looks up a record by id", buildJsonObject { put("type", "object"); putJsonObject("properties") { putJsonObject("id") { put("type", "string") } } })))
    }

    @get:Rule val compose = createAndroidComposeRule<SmokeActivity>()
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val server = MockWebServer()
    private val prompts = ConcurrentLinkedQueue<String>()
    private val replies = ConcurrentLinkedQueue<String>()
    private val sequence = AtomicInteger()
    private val owners = ViewModelStore()
    private lateinit var vm: AccountViewModel
    private lateinit var browser: DeepSeekWebView
    private lateinit var baseUrl: String

    private fun reset(servers: List<RemoteServer> = emptyList()) {
        DeepSeekSessionStore(app).clear()
        WebHistory(File(app.filesDir, "deepseek-web-chats.json")).clear()
        File(app.filesDir, "workspace/account").deleteRecursively()
        app.getSharedPreferences("deepseek_web_appearance", 0).edit().clear().commit()
        val settings = SettingsStore(app)
        settings.save(settings.load().copy(disabledTools = emptySet(), remoteServers = servers))
    }

    @Before fun setup() {
        reset()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.requestUrl!!.encodedPath) {
                "/api/v0/chat_session/create" -> envelope("""{"chat_session":{"id":"plugins-session"}}""")
                "/api/v0/users/current" -> envelope("""{"id":"user","email":"t***@example.com","mobile_number":"","need_birthday":false}""")
                "/api/v0/chat/create_pow_challenge" -> envelope("""{"challenge":{"algorithm":"DeepSeekHashV1","challenge":"abcdef","salt":"salt","difficulty":64,"expire_at":9999999999999,"signature":"signature"}}""")
                COMPLETION -> {
                    prompts += Json.parseToJsonElement(request.body.readUtf8()).jsonObject["prompt"]!!.jsonPrimitive.content
                    reply(replies.poll() ?: "Done.")
                }
                "/sign_in" -> MockResponse().setHeader("Content-Type", "text/html").setBody("<html><body>Mock sign-in</body></html>")
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()
        baseUrl = server.url("/").toString()
    }

    /** Starts the app on stored settings (call after changing them), signed in to the mock service. */
    private fun start() {
        compose.runOnUiThread {
            vm = AccountViewModel(app, baseUrl = baseUrl, powSolver = { 0L })
            owners.put("plugins", vm)
            browser = DeepSeekWebView(compose.activity, loginUrl = server.url("/sign_in").toString(), notice = vm::notice)
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
        val event = "data: " + buildJsonObject {
            put("p", ""); put("o", "SET")
            put("v", buildJsonObject {
                put("request_message_id", id - 1); put("response_message_id", id)
                put("response", buildJsonObject { put("fragments", buildJsonArray { add(buildJsonObject { put("type", "RESPONSE"); put("content", text) }) }) })
            })
        } + "\n\n"
        return MockResponse().setHeader("Content-Type", "text/event-stream").setBody(event + "data: [DONE]\n\n")
    }

    private fun toolBlock(name: String, arguments: JsonObject) = FENCE + "tool\n" + buildJsonObject { put("name", name); put("arguments", arguments) } + "\n$FENCE"

    private fun sendInAgentMode(prompt: String) {
        if (!vm.state.value.agent) {
            compose.onNodeWithTag("agent-toggle").performClick()
            compose.waitUntil(5_000) { vm.state.value.agent }
        }
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

    @Test fun switchesPersistAcrossARestartAndLeaveTheToolOutOfThePrompt() {
        start()
        compose.onNodeWithContentDescription("Open navigation").performClick()
        compose.onNodeWithTag("drawer-plugins").performClick()
        compose.waitUntil(5_000) { vm.state.value.page == AccountPage.PLUGINS }
        compose.onNodeWithTag("tool-web_search").performScrollTo().assertIsOn().performClick()
        compose.waitUntil(5_000) { "web_search" in vm.state.value.disabledTools }
        compose.onNodeWithTag("tool-web_search").assertIsOff()
        // A skill's functions have their own switches once the skills are read.
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("tool-data_analysis__describe_csv").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("tool-data_analysis__describe_csv").performScrollTo().assertIsOn().performClick()
        compose.waitUntil(5_000) { "data_analysis__describe_csv" in vm.state.value.disabledTools }
        capture("plugins-tools")

        // A new ViewModel (what an app restart creates) reads the same switches back.
        assertEquals(setOf("web_search", "data_analysis__describe_csv"), SettingsStore(app).load().disabledTools)
        lateinit var restarted: AccountViewModel
        compose.runOnUiThread { restarted = AccountViewModel(app, baseUrl = baseUrl, powSolver = { 0L }).also { owners.put("restarted", it) } }
        compose.waitUntil(15_000) { restarted.state.value.ready }
        assertEquals(setOf("web_search", "data_analysis__describe_csv"), restarted.state.value.disabledTools)

        compose.onNodeWithContentDescription("Back").performClick()
        compose.waitUntil(5_000) { vm.state.value.page == AccountPage.CHAT }
        sendInAgentMode("Look something up")
        compose.waitUntil(30_000) { !vm.state.value.busy && prompts.isNotEmpty() }
        val offered = prompts.first()
        assertFalse(offered.contains("- web_search("))
        assertFalse(offered.contains("- data_analysis__describe_csv("))
        assertTrue(offered.contains("- web_fetch("))
        assertTrue(offered.contains("- data_analysis__chart_csv("))
    }

    @Test fun aSwitchedOffToolRequestedAnywayIsRefusedAndTheRoutesDiffer() {
        start()
        compose.runOnUiThread { vm.setToolEnabled("create_pdf", false) }
        compose.waitUntil(5_000) { "create_pdf" in vm.state.value.disabledTools }
        replies += "Making it.\n" + toolBlock("create_pdf", buildJsonObject { put("title", "Report"); put("markdown", "# Report"); put("file_path", "report.pdf") })
        replies += "PDF files are switched off in this app."
        sendInAgentMode("Make a PDF report")
        compose.waitUntil(45_000) { !vm.state.value.busy && prompts.size == 2 }

        val offered = prompts.first()
        assertFalse(offered.contains("- create_pdf("))
        // The account route offers the document generators and web tools, never the API-key client's phone, browser or memory tools.
        listOf("write_file", "create_xlsx", "create_pptx", "create_chart", "create_image", "zip_files", "web_search", "web_fetch", "run_python")
            .forEach { assertTrue(it, offered.contains("- $it(")) }
        listOf("remember", "open_url", "draft_sms", "schedule_prompt", "code_interpreter").forEach { assertFalse(it, offered.contains("- $it(")) }
        val result = prompts.toList()[1]
        assertTrue(result.startsWith("${FENCE}tool_result\n"))
        assertTrue(result.contains("\"ok\":false") && result.contains("Denied by safety policy or unknown tool"))
        val reply = vm.state.value.messages.last()
        assertTrue(reply.artifacts.isEmpty())
        assertEquals(ToolStatus.DENIED, reply.steps.filterIsInstance<TurnStep.ToolCall>().single().status)
        assertTrue(File(app.filesDir, "workspace/account").walkTopDown().none { it.name.endsWith(".pdf") })
    }

    @Test fun remoteToolsAreOfferedAndAskBeforeEveryCall() {
        reset(listOf(SERVER))
        start()
        val name = RemoteTool.registryName(SERVER.id, "lookup")
        assertEquals(listOf(SERVER), vm.state.value.remoteServers)
        replies += "Checking.\n" + toolBlock(name, buildJsonObject { put("id", "42") })
        replies += "The lookup was declined."
        sendInAgentMode("Look up record 42")

        compose.waitUntil(30_000) { vm.state.value.confirmation != null }
        assertTrue(prompts.first().contains("- $name("))
        assertEquals(name, vm.state.value.confirmation!!.tool)
        compose.onNodeWithTag("tool-confirmation").assertTextContains("lookup on Fixture tools (https://mcp.example.com/mcp)", substring = true)
        compose.onNodeWithText("Deny").performClick()
        compose.waitUntil(30_000) { !vm.state.value.busy && prompts.size == 2 }
        assertTrue(prompts.toList()[1].contains("\"ok\":false") && prompts.toList()[1].contains("declined"))

        // The server's tools have switches on the Plugins & tools page; removing the server removes them.
        compose.runOnUiThread { vm.navigate(AccountPage.PLUGINS) }
        compose.onNodeWithTag("tool-$name").performScrollTo().assertIsOn()
        compose.runOnUiThread { vm.removeRemoteServer(SERVER.id) }
        compose.waitUntil(5_000) { vm.state.value.remoteServers.isEmpty() }
        compose.onAllNodesWithTag("tool-$name").assertCountEquals(0)
        assertTrue(SettingsStore(app).load().remoteServers.isEmpty())
    }
}
