package app.dsandroid.e2e

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The smallest release journey that proves the harness: a fresh app, a synthetic session through the normal
 * Paste session confirmation (the fixture validates it), and one streamed reply. Runs on the release APK only.
 */
@RunWith(AndroidJUnit4::class)
class BaselineJourneyTest {
    private val e2e = E2eDriver("baseline")

    @Test fun freshInstallPasteSessionAndStreamOneMessage() {
        assumeTrue("Mocked fixture runs only", Fixture.mode == "mocked")
        e2e.freshStart()
        e2e.waitFor(By.text("Paste session"), "sign-in routes", 30_000)
        e2e.step("signed-out")

        e2e.tap(By.text("Paste session"), "Paste session")
        e2e.type(By.clazz("android.widget.EditText"), Fixture.TOKEN, "session field")
        e2e.step("paste-session")
        e2e.tap(By.text("Sign in"), "Sign in")
        e2e.waitFor(E2eDriver.res("account-composer").enabled(true), "a usable composer after sign-in", 30_000)
        e2e.step("signed-in")

        e2e.type(E2eDriver.res("account-composer"), "Hello from the release journey", "composer")
        e2e.tap(By.desc("Send message"), "Send")
        e2e.waitFor(By.textContains(Fixture.PLAIN_REPLY), "the streamed reply", 90_000)
        e2e.step("streamed-reply")
    }
}
