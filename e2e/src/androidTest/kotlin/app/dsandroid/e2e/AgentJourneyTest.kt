package app.dsandroid.e2e

import android.content.ClipData
import android.content.Intent
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The release build's chat and agent journey against the fixture: DeepThink, Search and DeepThink + Search replies in the
 * live stream shapes, a file shared into the app and uploaded (its own proof of work, sent once DeepSeek processed it),
 * the chat "from another device" opened from the drawer and continued, then one agent turn per
 * account tool case in e2e/coverage.json (the fixture's "E2E-TOOL:<name>" marker makes the model call that tool). A PDF
 * goes through the system share sheet to the E2E receiver, which checks the bytes it got; Charts is switched off in
 * Plugins & tools and then refused; finally the session expires mid-chat and the app stays locked after a restart.
 * check_e2e_evidence.py --coverage verifies every case from the fixture's own log.
 */
@RunWith(AndroidJUnit4::class)
class AgentJourneyTest {
    private val e2e = E2eDriver("agent")
    private val received get() = File(e2e.context.filesDir, "received")

    private enum class Answer { NONE, ALLOW, DENY }

    @Test fun thinkSearchEveryToolAndAFileThroughTheShareSheet() {
        assumeTrue("Mocked fixture runs only", Fixture.mode == "mocked")
        received.deleteRecursively()
        e2e.freshStart()
        e2e.waitFor(E2eDriver.res("welcome-screen"), "the welcome screen", 30_000)
        e2e.tap(E2eDriver.res("welcome-paste"), "Paste session")
        e2e.tap(By.text("Agree"), "Agree to DeepSeek's terms")
        e2e.type(By.clazz("android.widget.EditText"), Fixture.AGENT_TOKEN, "session field")
        e2e.tap(By.text("Sign in"), "Sign in")
        e2e.waitFor(E2eDriver.res("account-composer").enabled(true), "a usable composer after sign-in", 30_000)

        e2e.tap(By.text("Think"), "the Think pill")
        send("Explain the E2E thinking check")
        e2e.waitFor(By.textContains("Thought for"), "the thinking time", 60_000)
        e2e.step("deepthink")
        e2e.tap(By.text("Think"), "the Think pill (off)")
        e2e.tap(By.text("Search"), "the Search pill")
        send("Search for the E2E fixture")
        // A Search message is confirmed first ("Send to DeepSeek?"), as it shares the query with DeepSeek Search.
        e2e.waitFor(By.text("Send to DeepSeek?"), "the Search send confirmation", 20_000)
        e2e.tap(By.text("Send"), "Send (confirm Search)")
        e2e.waitFor(By.textContains("web page"), "the searched pages", 60_000)
        e2e.assertAbsent(By.textContains("[citation:"), "a raw citation marker")
        e2e.step("search")

        // DeepThink + Search as the live service streams it: TOOL_SEARCH / TOOL_OPEN, statuses at -2 and -3, nested batches,
        // and a [reference:0] marker that must become a citation chip, never raw text.
        e2e.tap(By.text("Think"), "the Think pill (with Search)")
        send("Think and search the E2E fixture")
        e2e.waitFor(By.text("Send to DeepSeek?"), "the Think + Search send confirmation", 20_000)
        e2e.tap(By.text("Send"), "Send (confirm Think + Search)")
        e2e.waitFor(By.textContains("revenue grew 12%"), "the Think + Search answer", 90_000)
        e2e.assertAbsent(By.textContains("[reference:"), "a raw reference marker")
        e2e.assertAbsent(By.textContains("[citation:"), "a raw citation marker")
        e2e.step("think-search")
        e2e.tap(By.text("Think"), "the Think pill (off)")
        e2e.tap(By.text("Search"), "the Search pill (off)")

        // A file shared into the app goes up with its own proof of work and is referenced only after fetch_files said SUCCESS.
        shareIntoApp("notes.txt", "E2E upload content line\n", "Please read the attached notes")
        e2e.waitFor(By.textContains("notes.txt"), "the shared file in the composer", 30_000)
        e2e.tap(By.desc("Send message"), "Send (with the file)")
        e2e.waitFor(By.text("Send to DeepSeek?"), "the upload send confirmation", 20_000)
        e2e.tap(By.text("Send"), "Send (confirm the upload)")
        e2e.waitFor(By.textContains("I read notes.txt"), "the reply about the uploaded file", 90_000)
        e2e.waitFor(E2eDriver.res("file-chip-notes.txt"), "the file chip above the message", 10_000)
        e2e.step("upload")

        // The chat started "on another device" is in the drawer (DeepSeek's history); it loads and continues from its last reply.
        e2e.tap(By.desc("Open navigation"), "the drawer with DeepSeek's history")
        e2e.tap(By.text("Planets from the web"), "the chat from another device", 30_000)
        e2e.waitFor(By.text("Which planet is largest?"), "the messages loaded from DeepSeek", 30_000)
        e2e.step("server-chat-loaded")
        send("E2E-HISTORY what about its moons?")
        e2e.waitFor(By.textContains(Fixture.PLAIN_REPLY), "the reply in the continued chat", 90_000)
        e2e.step("server-chat-continued")
        e2e.tap(By.desc("New Chat"), "a new chat for the agent cases")

        e2e.tap(E2eDriver.res("agent-toggle"), "Agent mode")
        listOf("write_file", "write", "read", "present", "write_file_docx", "write_file_md").forEach { agent(it) }
        agent("create_pdf")
        shareBack("report.pdf", "application/pdf")
        listOf("create_xlsx", "create_pptx", "create_image", "create_chart", "zip_files", "code_interpreter", "skill").forEach { agent(it) }
        agent("web_search", answer = Answer.ALLOW)
        agent("web_fetch", answer = Answer.DENY, ok = false)
        // Native Python and skill functions need trusted execution, which is off on a fresh install: refused.
        listOf("run_python", "pip_install", "data_analysis__describe_csv").forEach { agent(it, ok = false) }

        e2e.tap(By.desc("Open navigation"), "the drawer")
        e2e.tap(E2eDriver.res("drawer-plugins"), "Plugins & tools")
        val charts = scrollTo(E2eDriver.res("tool-create_chart"), "the Charts switch")
        assertTrue("Charts starts on", charts.isChecked)
        charts.click()
        e2e.waitUntil("Charts switched off", 10_000) { e2e.find(E2eDriver.res("tool-create_chart"))?.isChecked == false }
        e2e.step("plugins-charts-off")
        e2e.device.pressBack()
        e2e.waitFor(E2eDriver.res("account-composer"), "the chat", 10_000)
        agent("create_chart", ok = false, step = "create-chart-switched-off")

        // DeepSeek rejects the session mid-chat: the app locks back to the welcome screen and stays there after a restart.
        send("E2E-EXPIRE now")
        e2e.waitFor(E2eDriver.res("welcome-screen"), "the welcome screen after the session expired", 60_000)
        e2e.assertAbsent(E2eDriver.res("account-composer"), "a composer after expiry")
        e2e.step("session-expired")
        e2e.restart()
        e2e.waitFor(E2eDriver.res("welcome-screen"), "the welcome screen after a restart", 60_000)
        e2e.assertAbsent(E2eDriver.res("account-composer"), "a composer after a restart")
        e2e.step("restarted-signed-out")
    }

    /** Shares a fixture file into the app with the system's ACTION_SEND, as any other app would. */
    private fun shareIntoApp(name: String, content: String, text: String) {
        val file = File(File(e2e.context.cacheDir, "fixtures").apply { mkdirs() }, name).apply { writeText(content) }
        val uri = FileProvider.getUriForFile(e2e.context, "app.dsandroid.e2e.files", file)
        val intent = Intent(Intent.ACTION_SEND).setPackage(Fixture.APP).setType("text/plain")
            .putExtra(Intent.EXTRA_STREAM, uri).putExtra(Intent.EXTRA_TEXT, text)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        intent.clipData = ClipData.newRawUri(name, uri)
        e2e.context.startActivity(intent)
    }

    private fun send(text: String) {
        e2e.type(E2eDriver.res("account-composer"), text, "composer")
        e2e.tap(By.desc("Send message"), "Send")
    }

    /** One agent turn for a fixture scenario; [answer] handles the approval dialog. The fixture's log is the record. */
    private fun agent(scenario: String, answer: Answer = Answer.NONE, ok: Boolean = true, step: String = scenario) {
        val tool = mapOf("write_file_docx" to "write_file", "write_file_md" to "write_file")[scenario] ?: scenario
        send("E2E-TOOL:$scenario please")
        when (answer) {
            Answer.ALLOW -> e2e.tap(By.text("Allow once"), "the approval for $tool", 60_000)
            Answer.DENY -> e2e.tap(By.text("Deny"), "the approval for $tool", 60_000)
            Answer.NONE -> Unit
        }
        e2e.device.wait(Until.hasObject(By.desc("Stop response")), 3_000)
        e2e.waitGone(By.desc("Stop response"), "the $tool turn", 120_000)
        val result = By.text(if (ok) "Done: $tool succeeded." else "Noted: $tool was not run.")
        // A tall file card can push the closing line below the screen: scroll the chat down while waiting for it.
        val deadline = System.currentTimeMillis() + 30_000
        while (e2e.find(result) == null && System.currentTimeMillis() < deadline) {
            e2e.find(E2eDriver.res("message-list"))?.scroll(Direction.DOWN, 0.8f)
            e2e.device.wait(Until.hasObject(result), 1_000)
        }
        e2e.waitFor(result, "the $tool result", 2_000)
        e2e.step(step)
    }

    /** Shares a generated file to the E2E receiver through the system share sheet, then checks what arrived. */
    private fun shareBack(fileName: String, mime: String) {
        e2e.tap(By.desc("Share $fileName"), "Share $fileName")
        val target = e2e.device.wait(Until.findObject(By.text("E2E receiver")), 10_000) ?: run {
            e2e.device.findObject(By.scrollable(true))?.scroll(Direction.DOWN, 0.8f)
            e2e.waitFor(By.text("E2E receiver"), "the E2E receiver in the share sheet", 10_000)
        }
        e2e.step("share-sheet")
        target.click()
        val file = File(received, fileName)
        e2e.waitUntil("$fileName to reach the receiver", 20_000) { file.length() > 0 && File(received, "$fileName.type").isFile }
        assertEquals(mime, File(received, "$fileName.type").readText())
        assertEquals("%PDF", file.inputStream().use { input -> ByteArray(4).also { check(input.read(it) == 4) }.toString(Charsets.US_ASCII) })
        val pages = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { PdfRenderer(it).use { renderer -> renderer.pageCount } }
        assertTrue("The shared PDF has no pages", pages >= 1)
        e2e.waitFor(E2eDriver.res("account-composer"), "the chat after sharing", 20_000)
        e2e.step("shared-$fileName")
    }

    private fun scrollTo(selector: BySelector, what: String) =
        (0 until 10).firstNotNullOfOrNull { e2e.find(selector) ?: run { e2e.device.findObject(By.scrollable(true))?.scroll(Direction.DOWN, 0.6f); null } }
            ?: e2e.waitFor(selector, what, 2_000)
}
