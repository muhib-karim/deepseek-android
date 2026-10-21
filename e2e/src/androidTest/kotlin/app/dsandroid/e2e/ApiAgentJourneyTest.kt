package app.dsandroid.e2e

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * v1.2.0 "Agent-first", the API-key front door against the fixture's OpenAI-compatible API: a fresh install, "Use your
 * DeepSeek API key (agent with tools)" on the welcome screen (no "More sign-in options"), the synthetic key checked with
 * GET /user/balance, Agent already on in API mode without a tap, the "What Agent can do" examples, then one request that
 * makes the model call create_pdf natively (tool_calls streamed in pieces). The tool card shows, expands to its
 * arguments, the PDF's file card offers Share, and the final answer streams. The fixture logs the round trip as the
 * "api_agent" journey only when the tool turn used deepseek-chat with the tools offered (e2e/coverage.json).
 */
@RunWith(AndroidJUnit4::class)
class ApiAgentJourneyTest {
    private val e2e = E2eDriver("api-agent")

    @Test fun freshInstallApiKeyFromWelcomeAgentOnToolCardAndFile() {
        assumeTrue("Mocked fixture runs only", Fixture.mode == "mocked")
        e2e.freshStart()
        e2e.waitFor(E2eDriver.res("welcome-screen"), "the welcome screen", 30_000)
        val button = scrollTo(E2eDriver.res("welcome-api-key"), "the API key button on the welcome screen")
        e2e.assertAbsent(E2eDriver.res("welcome-accounts"), "the hidden More sign-in options")
        e2e.step("welcome-api-key-button")
        button.click()
        // Dialogs are their own window: no test tags as resource ids there, so the field and button are found by class and text.
        e2e.type(By.clazz("android.widget.EditText"), Fixture.FIXTURE_PROFILE_VALUE, "API key field")
        e2e.tap(By.text("Check and use"), "Check and use")

        e2e.waitFor(E2eDriver.res("api-fallback-ui"), "API mode after the key was accepted", 60_000)
        val chip = e2e.waitFor(E2eDriver.res("api-agent-chip"), "the Agent chip", 30_000)
        assertTrue("Agent must be on in API mode without a tap", chip.isChecked || chip.isSelected)
        e2e.waitFor(E2eDriver.res("agent-examples"), "the What Agent can do card", 10_000)
        e2e.waitFor(E2eDriver.res("agent-hint"), "the Agent hint in the composer", 10_000)
        e2e.step("api-agent-on-by-default")

        // A tap on an example fills the composer; the journey then asks for the fixture's create_pdf scenario.
        e2e.tap(E2eDriver.res("agent-example-0"), "the first example prompt")
        e2e.waitFor(By.textContains("one-page PDF summary"), "the example in the composer", 10_000)
        val composer = e2e.waitFor(By.clazz("android.widget.EditText"), "the composer")
        composer.text = "E2E-TOOL:create_pdf make a one-page PDF report"
        e2e.device.waitForIdle()
        e2e.tap(By.desc("Send message"), "Send")

        val card = waitScrolling(E2eDriver.res("tool-step-create_pdf"), "the create_pdf tool card", 90_000)
        e2e.step("api-tool-card")
        card.click()
        waitScrolling(E2eDriver.res("tool-step-details"), "the expanded tool card", 10_000)
        waitScrolling(By.textContains("file_path: report.pdf"), "the tool arguments", 10_000)
        e2e.step("api-tool-card-expanded")
        waitScrolling(By.desc("Share report.pdf"), "Share on the PDF's file card", 30_000)
        waitScrolling(By.textContains("Native tool call finished."), "the streamed final answer", 60_000)
        e2e.step("api-file-card-and-answer")
    }

    private fun scrollTo(selector: androidx.test.uiautomator.BySelector, what: String) =
        (0 until 8).firstNotNullOfOrNull { e2e.find(selector) ?: run { e2e.device.findObject(By.scrollable(true))?.scroll(Direction.DOWN, 0.6f); null } }
            ?: e2e.waitFor(selector, what, 2_000)

    /** Waits for [selector], scrolling the chat down meanwhile (a tall card can push later content below the screen). */
    private fun waitScrolling(selector: androidx.test.uiautomator.BySelector, what: String, timeoutMs: Long): androidx.test.uiautomator.UiObject2 {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            e2e.find(selector)?.let { return it }
            e2e.device.findObject(By.scrollable(true))?.scroll(Direction.DOWN, 0.6f)
            e2e.device.wait(Until.hasObject(selector), 1_000)
        }
        return e2e.waitFor(selector, what, 1_000)
    }
}
