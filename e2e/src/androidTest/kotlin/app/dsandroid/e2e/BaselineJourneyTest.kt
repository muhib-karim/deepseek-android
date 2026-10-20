package app.dsandroid.e2e

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The smallest release journey that proves the harness and the sign-in gate: a fresh app shows only the welcome screen,
 * a session the fixture rejects keeps it locked, a synthetic session through the normal Paste session confirmation
 * (validated by the fixture) unlocks it, and one message streams back. Runs on the release APK only.
 */
@RunWith(AndroidJUnit4::class)
class BaselineJourneyTest {
    private val e2e = E2eDriver("baseline")

    @Test fun freshInstallPasteSessionAndStreamOneMessage() {
        assumeTrue("Mocked fixture runs only", Fixture.mode == "mocked")
        e2e.freshStart()
        e2e.waitFor(E2eDriver.res("welcome-screen"), "the welcome screen", 30_000)
        e2e.assertAbsent(E2eDriver.res("account-composer"), "a composer while signed out")
        e2e.assertAbsent(By.desc("Open navigation"), "the drawer while signed out")
        e2e.step("welcome")

        pasteSession("e2e-rejected-session-0123456789abcdef")
        e2e.waitFor(By.textContains("did not accept"), "the rejection notice", 30_000)
        e2e.assertAbsent(E2eDriver.res("account-composer"), "a composer after a rejected session")
        e2e.step("rejected-session")

        pasteSession(Fixture.TOKEN)
        e2e.waitFor(E2eDriver.res("account-composer").enabled(true), "a usable composer after sign-in", 30_000)
        e2e.step("signed-in")

        e2e.type(E2eDriver.res("account-composer"), "Hello from the release journey", "composer")
        e2e.tap(By.desc("Send message"), "Send")
        e2e.waitFor(By.textContains(Fixture.PLAIN_REPLY), "the streamed reply", 90_000)
        e2e.step("streamed-reply")
    }

    private fun pasteSession(token: String) {
        e2e.tap(E2eDriver.res("welcome-paste"), "Paste session")
        e2e.type(By.clazz("android.widget.EditText"), token, "session field")
        e2e.step("paste-session")
        e2e.tap(By.text("Sign in"), "Sign in")
    }
}
