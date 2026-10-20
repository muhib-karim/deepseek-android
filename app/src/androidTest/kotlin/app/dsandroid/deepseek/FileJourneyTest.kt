package app.dsandroid.deepseek

import android.app.Activity
import android.app.Application
import android.app.Instrumentation
import android.content.ContentUris
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.dsandroid.SmokeActivity
import app.dsandroid.core.deepseek.SessionToken
import app.dsandroid.core.deepseek.WebHistory
import app.dsandroid.core.tools.Artifact
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipInputStream
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
 * The real-file journey in account mode: agent tool blocks make code, Markdown, CSV, Word, PDF, Excel, PowerPoint, PNG
 * and SVG files; each file's bytes are checked, then Download, Save as (the system file picker, answered here with a file
 * this test owns) and Share hand over exactly those bytes. DeepSeek's web service is a mock.
 */
@RunWith(AndroidJUnit4::class)
class FileJourneyTest {
    companion object {
        private const val TOKEN = "file-journey-synthetic-token"
        private const val FENCE = "```"
        private const val KOTLIN = "fun main() {\n    println(\"Hello from the agent\")\n}\n"
        private const val NOTES = "# Notes\n\n- one\n- two\n"
        private const val CSV = "region,revenue\nNorth,1200\nSouth,980"
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
    private val exports get() = File(app.filesDir, "workspace/test-exports")

    private fun reset() {
        DeepSeekSessionStore(app).clear()
        WebHistory(File(app.filesDir, "deepseek-web-chats.json")).clear()
        File(app.filesDir, "workspace/account").deleteRecursively()
        exports.deleteRecursively()
        app.getSharedPreferences("deepseek_web_appearance", 0).edit().clear().commit()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) deleteOwnDownloads()
    }

    @Before fun setup() {
        reset()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.requestUrl!!.encodedPath) {
                "/api/v0/chat_session/create" -> envelope("""{"chat_session":{"id":"files-session"}}""")
                "/api/v0/users/current" -> envelope("""{"id":"user","email":"t***@example.com","mobile_number":"","need_birthday":false}""")
                "/api/v0/chat/create_pow_challenge" -> envelope("""{"challenge":{"algorithm":"DeepSeekHashV1","challenge":"abcdef","salt":"salt","difficulty":64,"expire_at":9999999999999,"signature":"signature"}}""")
                "/api/v0/chat/completion" -> {
                    prompts += Json.parseToJsonElement(request.body.readUtf8()).jsonObject["prompt"]!!.jsonPrimitive.content
                    reply(replies.poll() ?: "Done.")
                }
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()
        baseUrl = server.url("/").toString()
        compose.runOnUiThread {
            vm = AccountViewModel(app, baseUrl = baseUrl, powSolver = { 0L })
            owners.put("files", vm)
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

    private fun tool(name: String, arguments: JsonObjectBuilder.() -> Unit) =
        "Working on it.\n" + FENCE + "tool\n" + buildJsonObject { put("name", name); put("arguments", buildJsonObject(arguments)) } + "\n$FENCE"

    private fun send(prompt: String, expectedPrompts: Int) {
        if (!vm.state.value.agent) {
            compose.onNodeWithTag("agent-toggle").performClick()
            compose.waitUntil(5_000) { vm.state.value.agent }
        }
        compose.onNodeWithTag("account-composer").performTextReplacement(prompt)
        compose.onNodeWithContentDescription("Send message").performClick()
        compose.waitUntil(60_000) { !vm.state.value.busy && prompts.size == expectedPrompts }
        assertNull(vm.state.value.error)
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    private fun zipEntries(file: File): Map<String, String> = ZipInputStream(file.inputStream()).use { zip ->
        generateSequence { zip.nextEntry }.associate { it.name to zip.readBytes().toString(Charsets.UTF_8) }
    }
    private fun fileOf(artifact: Artifact) = File(app.filesDir, "workspace/${artifact.path}")

    /** Intercepts the next start of [action] (no system UI opens), answering it with [result]. */
    private class Capture(private val action: String, private val result: () -> Intent?) : Instrumentation.ActivityMonitor() {
        @Volatile var intent: Intent? = null
        override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
            if (intent.action != action) return null
            this.intent = intent
            return Instrumentation.ActivityResult(Activity.RESULT_OK, result())
        }
    }
    private fun <T> capturing(action: String, result: () -> Intent? = { null }, block: (Capture) -> T): T {
        val capture = Capture(action, result)
        InstrumentationRegistry.getInstrumentation().addMonitor(capture)
        try { return block(capture) } finally { InstrumentationRegistry.getInstrumentation().removeMonitor(capture) }
    }
    private fun cardAction(fileName: String, description: String) {
        compose.onNodeWithTag("message-list").performScrollToNode(hasTestTag("artifact-card-$fileName"))
        compose.onNodeWithContentDescription(description).performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
    }

    @Test fun everyFormatIsMadeCheckedAndHandedOverByteForByte() {
        replies += tool("write") { put("file_path", "main.kt"); put("content", KOTLIN) }
        replies += tool("present") { putJsonArray("files") { addJsonObject { put("path", "main.kt") } } }
        replies += tool("write_file") { put("file_path", "notes.md"); put("content", NOTES) }
        replies += tool("write_file") { put("file_path", "data.csv"); put("content", CSV) }
        replies += tool("write_file") { put("file_path", "report.docx"); put("content", "# Report\n\nRevenue grew **12%**.") }
        replies += "The code, notes, table and report are ready."
        send("Make the code, notes, table and report", 6)
        replies += tool("create_pdf") { put("title", "Summary"); put("markdown", "# Summary\n\nAll regions grew."); put("file_path", "summary.pdf") }
        replies += tool("create_xlsx") { put("fileName", "figures.xlsx"); putJsonArray("sheets") { addJsonObject {
            put("name", "Q1"); putJsonArray("rows") { addJsonArray { add("Region"); add("Revenue") }; addJsonArray { add("North"); add(1200) } } } } }
        replies += tool("create_pptx") { put("fileName", "deck.pptx"); put("title", "Quarter"); putJsonArray("slides") { addJsonObject {
            put("title", "Highlights"); putJsonArray("bullets") { add("North led"); add("South recovered") } } } }
        replies += tool("create_image") { put("fileName", "badge.png")
            put("svg", "<svg xmlns='http://www.w3.org/2000/svg' width='120' height='80'><rect width='120' height='80' fill='#3D63F5'/></svg>") }
        replies += tool("create_chart") { put("fileName", "sales.svg"); put("type", "bar"); put("title", "Sales")
            putJsonArray("labels") { add("Q1"); add("Q2") }; putJsonArray("series") { addJsonObject { put("name", "Sales"); putJsonArray("values") { add(3); add(5) } } } }
        replies += "The PDF, workbook, deck, image and chart are ready."
        send("Now the PDF, workbook, deck, image and chart", 12)

        val artifacts = vm.state.value.messages.filter { !it.user }.flatMap { it.artifacts }.associateBy { it.fileName }
        assertEquals(setOf("main.kt", "notes.md", "data.csv", "report.docx", "summary.pdf", "figures.xlsx", "deck.pptx", "badge.png", "sales.svg"), artifacts.keys)
        // Every file is in this chat's folder of the signed-in account, never in the shared workspace root.
        artifacts.values.forEach { assertTrue(it.path, it.path.startsWith("account/") && it.path.count { c -> c == '/' } >= 2) }
        assertEquals(KOTLIN, fileOf(artifacts.getValue("main.kt")).readText())
        assertEquals(NOTES, fileOf(artifacts.getValue("notes.md")).readText())
        assertEquals(listOf(listOf("region", "revenue"), listOf("North", "1200"), listOf("South", "980")),
            fileOf(artifacts.getValue("data.csv")).readText().trim().lines().map { it.trim().split(',') })
        assertTrue(zipEntries(fileOf(artifacts.getValue("report.docx"))).getValue("word/document.xml").contains("Revenue grew"))
        assertTrue(zipEntries(fileOf(artifacts.getValue("figures.xlsx"))).let { it.containsKey("xl/workbook.xml") && it.values.any { xml -> xml.contains("North") } })
        assertTrue(zipEntries(fileOf(artifacts.getValue("deck.pptx"))).let { it.containsKey("ppt/presentation.xml") && it.values.any { xml -> xml.contains("Highlights") } })
        ParcelFileDescriptor.open(fileOf(artifacts.getValue("summary.pdf")), ParcelFileDescriptor.MODE_READ_ONLY).use { pdf ->
            PdfRenderer(pdf).use { assertTrue(it.pageCount >= 1) }
        }
        val png = BitmapFactory.decodeFile(fileOf(artifacts.getValue("badge.png")).path)
        assertNotNull("PNG does not decode", png)
        assertTrue(png.width > 0 && png.height > 0)
        fileOf(artifacts.getValue("sales.svg")).readText().let { assertTrue(it.trimStart().startsWith("<svg") && it.contains("Sales")) }
        mapOf("main.kt" to "text/plain", "data.csv" to "text/csv", "summary.pdf" to "application/pdf", "badge.png" to "image/png",
            "report.docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document").forEach { (name, mime) ->
            assertEquals(name, mime, artifacts.getValue(name).mimeType)
        }

        // Save as: the system file picker gets the file's own name and type; the bytes reach the document it returns.
        exports.mkdirs()
        for (name in listOf("report.docx", "badge.png")) {
            val artifact = artifacts.getValue(name)
            val target = File(exports, "saved-$name")
            val uri = FileProvider.getUriForFile(app, "${app.packageName}.artifacts", target.apply { writeBytes(ByteArray(0)) })
            capturing(Intent.ACTION_CREATE_DOCUMENT, { Intent().setData(uri) }) { capture ->
                cardAction(name, "Save $name as")
                compose.waitUntil(10_000) { target.length() == fileOf(artifact).length() }
                assertEquals(artifact.mimeType, capture.intent!!.type)
                assertEquals(name, capture.intent!!.getStringExtra(Intent.EXTRA_TITLE))
            }
            assertEquals(sha256(fileOf(artifact).readBytes()), sha256(target.readBytes()))
        }

        // Share: one file, its own type, a read grant, and the same bytes through the grant's URI.
        val pdf = artifacts.getValue("summary.pdf")
        capturing(Intent.ACTION_CHOOSER) { capture ->
            cardAction("summary.pdf", "Share summary.pdf")
            compose.waitUntil(10_000) { capture.intent != null }
            @Suppress("DEPRECATION") val send = capture.intent!!.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
            assertEquals(Intent.ACTION_SEND, send.action)
            assertEquals("application/pdf", send.type)
            assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            @Suppress("DEPRECATION") val stream = send.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)!!
            val shared = app.contentResolver.openInputStream(stream)!!.use { it.readBytes() }
            assertEquals(sha256(fileOf(pdf).readBytes()), sha256(shared))
        }

        // Download: Downloads/DeepSeek through MediaStore on Android 10+, the file picker before (covered by Save as above).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val xlsx = artifacts.getValue("figures.xlsx")
            cardAction("figures.xlsx", "Save figures.xlsx to Downloads")
            var downloaded: ByteArray? = null
            compose.waitUntil(10_000) { downloaded = readOwnDownload("figures.xlsx"); downloaded != null }
            assertEquals(sha256(fileOf(xlsx).readBytes()), sha256(downloaded!!))
        }

        // A file that disappeared says so instead of opening a broken viewer.
        val chart = artifacts.getValue("sales.svg")
        assertTrue(fileOf(chart).delete())
        cardAction("sales.svg", "Open sales.svg")
        compose.waitUntil(5_000) { vm.state.value.error == "This file is no longer available." }

        // After a restart the chat still lists its files, and they are still on disk.
        val chatId = vm.state.value.conversationId
        lateinit var restarted: AccountViewModel
        compose.runOnUiThread { restarted = AccountViewModel(app, baseUrl = baseUrl, powSolver = { 0L }).also { owners.put("restarted", it) } }
        compose.waitUntil(15_000) { restarted.state.value.ready && restarted.state.value.history.any { it.id == chatId } }
        compose.runOnUiThread { restarted.open(chatId) }
        compose.waitUntil(10_000) { restarted.state.value.conversationId == chatId }
        val kept = restarted.state.value.messages.flatMap { it.artifacts }.map { it.fileName }.toSet()
        assertEquals(artifacts.keys, kept)
        assertTrue(fileOf(artifacts.getValue("report.docx")).isFile)
    }

    private fun downloadsQuery(name: String?) = app.contentResolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
        arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME),
        "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?" + if (name != null) " AND ${MediaStore.MediaColumns.DISPLAY_NAME} = ?" else "",
        listOfNotNull("${android.os.Environment.DIRECTORY_DOWNLOADS}/DeepSeek%", name).toTypedArray(), null)

    private fun readOwnDownload(name: String): ByteArray? = downloadsQuery(name)?.use { cursor ->
        if (!cursor.moveToFirst()) null
        else app.contentResolver.openInputStream(ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cursor.getLong(0)))?.use { it.readBytes() }
    }

    /** Removes what this test saved to Downloads/DeepSeek (the app's own entries, so no permission is needed). */
    private fun deleteOwnDownloads() {
        val names = setOf("figures.xlsx", "report.docx", "badge.png", "summary.pdf")
        runCatching {
            downloadsQuery(null)?.use { cursor ->
                while (cursor.moveToNext()) if (cursor.getString(1) in names) {
                    app.contentResolver.delete(ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cursor.getLong(0)), null, null)
                }
            }
        }
    }
}
