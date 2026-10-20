package app.dsandroid.deepseek

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.app.ActivityOptionsCompat
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.dsandroid.SmokeActivity
import app.dsandroid.core.deepseek.SessionToken
import app.dsandroid.core.deepseek.WebHistory
import app.dsandroid.core.workbench.VersionOrigin
import app.dsandroid.core.workbench.WorkbenchStore
import app.dsandroid.workbench.WorkbenchPane
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

/** Real ViewModel, shell, artifacts panel and editor; only DeepSeek's web service is a mock and Save as goes to a recording registry. */
@RunWith(AndroidJUnit4::class)
class WorkbenchTest {
    companion object {
        private const val TOKEN = "workbench-synthetic-token"
        private const val COMPLETION = "/api/v0/chat/completion"
        private const val FENCE = "```"
        private val PAGE = listOf("<!doctype html>", "<html><head><title>Counter</title></head>", "<body>",
            "<button id=\"b\" onclick=\"this.textContent = Number(this.textContent) + 1\">0</button>", "</body>", "</html>").joinToString("\n")
    }

    @get:Rule val compose = createAndroidComposeRule<SmokeActivity>()
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val server = MockWebServer()
    private val completions = ConcurrentLinkedQueue<JsonObject>()
    private val replies = ConcurrentLinkedQueue<String>()
    private val sequence = AtomicInteger()
    private val owners = ViewModelStore()
    private val launched = ConcurrentLinkedQueue<Intent>()
    private lateinit var vm: AccountViewModel
    private lateinit var browser: DeepSeekWebView

    /** Records every launched intent and answers "cancelled", so no system picker opens during the test. */
    private val registryOwner = object : ActivityResultRegistryOwner {
        override val activityResultRegistry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
                launched += contract.createIntent(compose.activity, input)
                dispatchResult(requestCode, Activity.RESULT_CANCELED, null)
            }
        }
    }

    private fun reset() {
        DeepSeekSessionStore(app).clear()
        WebHistory(File(app.filesDir, "deepseek-web-chats.json")).clear()
        File(app.filesDir, "workspace/account").deleteRecursively()
        File(app.filesDir, "workbench").deleteRecursively()
        app.getSharedPreferences("deepseek_web_appearance", 0).edit().clear().commit()
    }

    @Before fun setup() {
        reset()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.requestUrl!!.encodedPath) {
                "/api/v0/chat_session/create" -> envelope("""{"chat_session":{"id":"workbench-session"}}""")
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
            owners.put("workbench", vm)
            browser = DeepSeekWebView(compose.activity, loginUrl = signInUrl, notice = vm::notice)
        }
        compose.waitUntil(15_000) { vm.state.value.ready }
        runBlocking { withContext(Dispatchers.Main) { assertTrue(vm.acceptSession(SessionToken.parse(TOKEN)!!)) } }
        compose.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides registryOwner) {
                DeepSeekAccountApp(vm = vm, loginBrowser = browser)
            }
        }
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

    private fun send(prompt: String) {
        compose.onNodeWithTag("account-composer").performTextReplacement(prompt)
        compose.onNodeWithContentDescription("Send message").performClick()
        compose.waitUntil(30_000) { !vm.state.value.busy && vm.state.value.messages.any { !it.user && it.text.isNotEmpty() } }
        assertNull(vm.state.value.error)
    }

    private fun panel(): WorkbenchPane.Panel? = vm.state.value.pane as? WorkbenchPane.Panel

    private fun openFirstCodeBlock(tag: String) {
        compose.onNodeWithTag("message-list").performScrollToNode(hasTestTag(tag))
        compose.onNodeWithTag(tag).performClick()
    }

    private fun capture(name: String) {
        runCatching {
            compose.waitForIdle()
            val directory = File(compose.activity.getExternalFilesDir(null), "smoke-screenshots").apply { mkdirs() }
            val bitmap = compose.onAllNodes(isRoot()).onLast().captureToImage().asAndroidBitmap()
            File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    @Test fun htmlArtifactOpensEditsRunsAndShowsV2() {
        replies += "Here is a counter page.\n\n${FENCE}html\n$PAGE\n$FENCE"
        send("Make a counter page")
        openFirstCodeBlock("code-preview")

        compose.waitUntil(10_000) { panel() != null }
        compose.onNodeWithTag("artifact-panel").assertIsDisplayed()
        compose.onNodeWithTag("version-picker").assertTextContains("v1")
        compose.onNodeWithTag("artifact-diff").assertTextEquals("v1 · From the answer")
        compose.onNodeWithTag("html-preview").assertExists()
        assertEquals("Counter", panel()!!.artifact.title)

        compose.onNodeWithTag("tab-source").performClick()
        val edited = PAGE.replace(">0</button>", ">10</button>")
        compose.onNodeWithTag("code-editor").performTextReplacement(edited)
        compose.onNodeWithText("Edited. Run makes v2.").assertIsDisplayed()
        compose.onNodeWithTag("artifact-run").performClick()

        compose.waitUntil(10_000) { panel()?.artifact?.latest?.number == 2 }
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag("version-picker") and hasText("v2", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("artifact-diff").assertTextEquals("v2 · Your edit · +1 -1 lines")
        compose.onNodeWithTag("html-preview").assertExists()
        val artifact = panel()!!.artifact
        assertEquals(edited, artifact.latest.source)
        assertEquals(VersionOrigin.EDIT, artifact.latest.origin)
        assertEquals(PAGE, artifact.version(1)!!.source)
        // Versions are kept per account outside the agent's chat folder.
        val stored = WorkbenchStore(File(app.filesDir, "workbench/${DeepSeekSessionStore.DEFAULT_SLOT}")).load(artifact.id)
        assertEquals(listOf(1, 2), stored!!.versions.map { it.number })
        capture("workbench-html-v2")

        compose.onNodeWithTag("version-picker").performClick()
        compose.onNodeWithTag("version-1").performClick()
        compose.onNodeWithTag("artifact-diff").assertTextEquals("v1 · From the answer")
        compose.onNodeWithText("Close").performClick()
        compose.waitUntil(5_000) { vm.state.value.pane == null }
    }

    @Test fun askDeepSeekSendsTheEditedSourceAndItsReplyBecomesTheNextVersion() {
        replies += "Here is a counter page.\n\n${FENCE}html\n$PAGE\n$FENCE"
        val revised = PAGE.replace("<button id=\"b\"", "<button id=\"b\" style=\"background:#426EFE;color:white\"")
        replies += "Updated the button.\n\n${FENCE}html\n$revised\n$FENCE\nThe button is blue now."
        send("Make a counter page")
        openFirstCodeBlock("code-preview")
        compose.waitUntil(10_000) { panel() != null }

        compose.onNodeWithTag("artifact-ask").performClick()
        compose.onNodeWithTag("revision-instruction").performTextInput("Make the button blue")
        compose.onNodeWithTag("revision-send").performClick()

        compose.waitUntil(30_000) { completions.size == 2 && !vm.state.value.busy && panel()?.artifact?.latest?.number == 2 }
        assertNull(vm.state.value.error)
        val request = completions.toList()[1]
        val prompt = request["prompt"]!!.jsonPrimitive.content
        assertTrue(prompt.startsWith("Please update the HTML artifact \"Counter\" (version 1)."))
        assertTrue(prompt.contains("Change request: Make the button blue\n"))
        assertTrue(prompt.endsWith("${FENCE}html\n$PAGE\n$FENCE"))
        // The request is the next message in the same chat.
        assertEquals("workbench-session", request["chat_session_id"]!!.jsonPrimitive.content)
        assertEquals(2L, request["parent_message_id"]!!.jsonPrimitive.long)

        val artifact = panel()!!.artifact
        assertEquals(revised, artifact.latest.source)
        assertEquals(VersionOrigin.DEEPSEEK, artifact.latest.origin)
        assertFalse(panel()!!.revising)
        compose.onNodeWithTag("artifact-diff").assertTextEquals("v2 · DeepSeek revision · +1 -1 lines")
        val messages = vm.state.value.messages
        assertEquals("Change “Counter” (v1): Make the button blue", messages[messages.size - 2].text)
        assertEquals(artifact.id, messages.last().revisionOf)
        capture("workbench-ask-deepseek-v2")
    }

    @Test fun editorSaveAsLaunchesCreateDocumentAndSaveWritesTheChatFolder() {
        replies += "Run this:\n\n${FENCE}python\nprint(2 + 2)\n$FENCE"
        send("Show me Python")
        openFirstCodeBlock("code-edit")
        compose.waitUntil(10_000) { vm.state.value.pane is WorkbenchPane.Editor }
        compose.onNodeWithTag("code-editor-screen").assertIsDisplayed()
        compose.onNodeWithTag("editor-run").assertExists()

        compose.onNodeWithTag("editor-save-as").performClick()
        compose.waitUntil(5_000) { launched.isNotEmpty() }
        val intent = launched.single()
        assertEquals(Intent.ACTION_CREATE_DOCUMENT, intent.action)
        assertEquals("snippet.py", intent.getStringExtra(Intent.EXTRA_TITLE))
        assertTrue(intent.categories.orEmpty().contains(Intent.CATEGORY_OPENABLE))
        capture("workbench-editor")

        compose.onNodeWithTag("code-editor").performTextReplacement("print(6 * 7)")
        compose.onNodeWithTag("editor-save").performClick()
        val chatFolder = File(app.filesDir, "workspace/account/${vm.state.value.conversationId}")
        compose.waitUntil(10_000) { File(chatFolder, "snippet.py").isFile }
        assertEquals("print(6 * 7)", File(chatFolder, "snippet.py").readText())
        compose.waitUntil(5_000) { (vm.state.value.pane as? WorkbenchPane.Editor)?.file?.path != null }
    }
}
