package app.dsandroid.e2e

import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import java.io.File
import java.util.regex.Pattern

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

    /** Opens the app from its launcher entry; one retry, and diagnostics when it does not appear (slow cold start, ANR, crash). */
    fun launch() {
        repeat(2) { attempt ->
            shell("am start -W -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n ${Fixture.APP}/.MainActivity")
            val end = SystemClock.uptimeMillis() + 60_000
            while (SystemClock.uptimeMillis() < end) {
                if (device.hasObject(By.pkg(Fixture.APP))) { device.waitForIdle(); return }
                // A slow first start can raise "isn't responding" for the app or the system UI: wait, never close.
                device.findObject(By.text(Pattern.compile("(?i)wait")).pkg("android"))?.click()
                SystemClock.sleep(500)
            }
            launchDiagnostics("launch-attempt-${attempt + 1}")
        }
        throw AssertionError("The app did not open after two launches (diagnostics saved)")
    }

    /** Text diagnostics next to the step screenshots: crash log, resumed activity, focus, process. */
    fun launchDiagnostics(label: String) {
        val activities = shell("dumpsys activity activities").lines().filter { "ResumedActivity" in it || "mFocusedApp" in it }.joinToString("\n")
        val focus = shell("dumpsys window").lines().filter { "mCurrentFocus" in it || "mFocusedApp" in it }.joinToString("\n")
        val text = buildString {
            appendLine("pidof ${Fixture.APP}: ${shell("pidof ${Fixture.APP}").trim().ifEmpty { "(not running)" }}")
            appendLine("current package: ${device.currentPackageName}")
            appendLine("--- resumed activity"); appendLine(activities.trim())
            appendLine("--- window focus"); appendLine(focus.trim())
            appendLine("--- logcat crash buffer"); appendLine(shell("logcat -d -b crash").trim())
            appendLine("--- logcat (last 300 lines)"); appendLine(shell("logcat -d -t 300").trim())
        }
        File(steps, "${label.lowercase()}-diagnostics.txt").writeText(text)
        step(label)
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

    /**
     * Waits until [condition] holds, polling the screen; fails with [what]. A view that changes while the condition reads
     * it (StaleObjectException, common inside a loading WebView) counts as "not yet".
     */
    fun waitUntil(what: String, timeoutMs: Long = 20_000, condition: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + timeoutMs
        while (!(try { condition() } catch (_: StaleObjectException) { false })) {
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
        screenshot(File(steps, "$base.png"))
        runCatching { File(steps, "$base.xml").outputStream().use { device.dumpWindowHierarchy(it) } }
    }

    /** UiAutomation's screenshot; where it returns nothing (seen on API 26 emulators), the shell's screencap instead. */
    private fun screenshot(file: File) {
        if (runCatching { device.takeScreenshot(file) }.getOrDefault(false) && file.length() > 0) return
        runCatching {
            ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("screencap -p")).use { input ->
                file.outputStream().use { input.copyTo(it) }
            }
        }
        if (file.length() < 8) { file.delete(); println("E2E step screenshot failed: ${file.name}") }
    }

    companion object {
        fun res(tag: String): BySelector = By.res(tag)
        fun text(value: String): BySelector = By.text(value)
        fun desc(value: String): BySelector = By.desc(value)
    }
}
