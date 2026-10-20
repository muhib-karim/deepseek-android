package app.dsandroid.e2e

import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import java.io.File

/** Values shared with e2e/fixture/server.py. */
object Fixture {
    const val APP = "app.dsandroid"
    /** Synthetic sessions: the fixture accepts any token with this prefix until a test revokes it. */
    const val TOKEN = "e2e-synthetic-session-0123456789abcdef"
    const val SECOND_TOKEN = "e2e-synthetic-session-second-0123456789"
    /** Synthetic API-key profile value; only the fixture accepts it (it is not a credential). */
    const val FIXTURE_PROFILE_VALUE = "e2e-synthetic-profile-0123456789" // gitleaks:allow
    const val PLAIN_REPLY = "from the E2E fixture"
    /** Instrumentation argument: "mocked" (fixture routing) or "live" (the real sign-in page, nothing else). */
    val mode: String get() = InstrumentationRegistry.getArguments().getString("mode", "mocked")
}

/**
 * Drives the installed release build of app.dsandroid from outside, as a person would: launcher intent, taps on
 * visible text, content descriptions and test tags (exposed as resource ids), the system share sheet and browser.
 * Every step leaves a screenshot and a window hierarchy under the host app's files (pulled by run_release_e2e.sh).
 */
class E2eDriver(journey: String) {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    val device: UiDevice = UiDevice.getInstance(instrumentation)
    val context = instrumentation.targetContext
    private val steps = File(context.getExternalFilesDir(null), "e2e-steps/$journey").apply { mkdirs() }
    private var index = 0

    fun shell(command: String): String = device.executeShellCommand(command)

    /** Clears the app's data (a fresh install as far as the app can tell) and opens it from its launcher entry. */
    fun freshStart() {
        shell("am force-stop ${Fixture.APP}")
        shell("pm clear ${Fixture.APP}")
        launch()
    }

    fun launch() {
        shell("am start -W -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n ${Fixture.APP}/.MainActivity")
        check(device.wait(Until.hasObject(By.pkg(Fixture.APP).depth(0)), 30_000)) { "The app did not open" }
        device.waitForIdle()
    }

    fun restart() {
        shell("am force-stop ${Fixture.APP}")
        launch()
    }

    fun find(selector: BySelector): UiObject2? = device.findObject(selector)
    fun exists(selector: BySelector): Boolean = device.hasObject(selector)

    fun waitFor(selector: BySelector, what: String, timeoutMs: Long = 20_000): UiObject2 =
        device.wait(Until.findObject(selector), timeoutMs) ?: run {
            step("missing-$what")
            throw AssertionError("Not shown within ${timeoutMs / 1000} s: $what")
        }

    fun waitGone(selector: BySelector, what: String, timeoutMs: Long = 20_000) {
        if (device.wait(Until.gone(selector), timeoutMs) != true) {
            step("still-shown-$what")
            throw AssertionError("Still shown after ${timeoutMs / 1000} s: $what")
        }
    }

    fun tap(selector: BySelector, what: String, timeoutMs: Long = 20_000) {
        waitFor(selector, what, timeoutMs).click()
        device.waitForIdle()
    }

    fun type(selector: BySelector, text: String, what: String) {
        val field = waitFor(selector, what)
        field.click()
        field.text = text
        device.waitForIdle()
    }

    fun assertAbsent(selector: BySelector, what: String) {
        if (device.hasObject(selector)) {
            step("unexpected-$what")
            throw AssertionError("Must not be shown: $what")
        }
    }

    /** Waits until [condition] holds, polling the screen; fails with [what]. */
    fun waitUntil(what: String, timeoutMs: Long = 20_000, condition: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + timeoutMs
        while (!condition()) {
            if (SystemClock.uptimeMillis() > end) {
                step("timeout-$what")
                throw AssertionError("Timed out: $what")
            }
            SystemClock.sleep(250)
        }
    }

    /** Numbered screenshot and window hierarchy for the evidence artifact. */
    fun step(name: String) {
        index++
        val base = "%02d-%s".format(index, name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(60))
        device.waitForIdle()
        device.takeScreenshot(File(steps, "$base.png"))
        runCatching { File(steps, "$base.xml").outputStream().use { device.dumpWindowHierarchy(it) } }
    }

    companion object {
        fun res(tag: String): BySelector = By.res(tag)
        fun text(value: String): BySelector = By.text(value)
        fun desc(value: String): BySelector = By.desc(value)
    }
}
