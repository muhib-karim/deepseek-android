package app.dsandroid.engine

import android.view.KeyEvent
import android.webkit.WebView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.FixMethodOrder
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * The official engine on the emulator with the x86_64 payload (CI: emulator.yml, job "engine"): the bundled node runs
 * JavaScript, the harness service reaches the full UI with a fake key (no DeepSeek call is made), the screens have no
 * overlap or stray background (screenshots), and an installed skill shows in the "/" palette and leaves it when disabled.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class EngineRuntimeTest {
    @Before
    fun grant() = V130Shots.grantNotifications()

    @get:Rule
    val compose = createEmptyComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    private fun awaitTag(tag: String, timeoutMs: Long) =
        compose.waitUntil(timeoutMs) { compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }

    private fun awaitReady(timeoutMs: Long) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline && NodeServer.state() != NodeServer.State.READY) {
            check(NodeServer.state() != NodeServer.State.FAILED) { "engine failed: ${NodeServer.detail()}\n${EngineLog.tail(context, 6000)}" }
            Thread.sleep(500)
        }
        check(NodeServer.state() == NodeServer.State.READY) { "engine not ready: ${NodeServer.state()}\n${EngineLog.tail(context, 6000)}" }
    }

    @Test
    fun a_payloadExtractsAndTheHarnessServesTheFullUi() {
        assertNotNull("this build carries the engine payload for the emulator ABI", PayloadSource.find(context))
        Engine.setKey(context, "sk-ci-fake-key-0000000000000000")
        ActivityScenario.launch(EngineActivity::class.java).use {
            // First launch: the one-time unpack shows its progress on an opaque cover.
            compose.waitUntil(60_000) {
                NodeServer.state() == NodeServer.State.PREPARING || NodeServer.state() == NodeServer.State.STARTING || NodeServer.state() == NodeServer.State.READY
            }
            if (NodeServer.state() == NodeServer.State.PREPARING) {
                awaitTag("engine-progress", 10_000)
                V130Shots.take("03-preparing")
            }
            awaitReady(TimeUnit.MINUTES.toMillis(8))
            assertTrue("full UI page, not the 3709-byte empty plugin graph: ${NodeServer.pageBytes()}", NodeServer.pageBytes() >= NodeServer.FULL_UI_MIN_BYTES)
            val credentials = DshCredentials.file(Engine.dshHome(context))
            assertTrue(credentials.readText().contains("DEEPSEEK_API_KEY"))
            assertEquals("rw-------", java.nio.file.Files.getPosixFilePermissions(credentials.toPath()).let { java.nio.file.attribute.PosixFilePermissions.toString(it) })
        }
    }

    @Test
    fun b_bundledNodeRunsJavaScript() {
        val steps = RuntimeProbe(Engine.runtime(context), Engine.home(context)).runAll()
        val js = steps.first { it.name == "node: run javascript" }
        assertTrue(js.render(), js.ok() && js.stdout.contains("NODE_JS_OK") && js.stdout.contains("android"))
        val version = steps.first { it.name == "harness: dsh --version" }
        assertTrue(version.render(), version.ok())
        assertTrue(steps.first { it.name == "bash: trivial command" }.ok())
    }

    @Test
    fun c_chatMenuAndKeyboardScreens() {
        ActivityScenario.launch(EngineActivity::class.java).use {
            awaitReady(TimeUnit.MINUTES.toMillis(3))
            compose.waitUntil(60_000) { compose.onAllNodesWithTag("engine-preparing", useUnmergedTree = true).fetchSemanticsNodes().isEmpty() }
            Thread.sleep(3000) // the harness UI finishes its first render
            compose.onNodeWithTag("engine-webview", useUnmergedTree = true).assertIsDisplayed()
            V130Shots.take("04-engine-chat")
            compose.onNodeWithTag("engine-menu", useUnmergedTree = true).performClick()
            for (item in listOf("menu-api-key", "menu-stats", "menu-harness", "menu-status", "menu-advanced", "menu-about")) {
                compose.onNodeWithTag(item, useUnmergedTree = true).assertIsDisplayed()
            }
            V130Shots.take("05-overflow-menu")
            device.pressBack()
            compose.waitForIdle()
            // Keyboard open: the composer must stay above it, fully visible (imePadding on the engine view).
            val composer = device.wait(Until.findObject(By.clazz("android.widget.EditText")), 20_000)
                ?: device.wait(Until.findObject(By.textContains("Message")), 10_000)
            if (composer != null) {
                composer.click()
                Thread.sleep(2500)
                V130Shots.take("06-keyboard-open")
                device.pressBack()
            }
        }
    }

    @Test
    fun d_installedSkillAppearsInTheSlashPaletteAndLeavesWhenDisabled() {
        val skill = File(context.cacheDir, "fixture/ci-fixture-skill").apply { mkdirs() }
        File(skill, "SKILL.md").writeText("---\nname: ci-fixture-skill\ndescription: Fixture skill for the palette test\n---\n\nSay hello.\n")
        val store = Engine.plugins(context)
        runCatching { store.remove("ci-fixture-skill") }
        store.install(skill.absolutePath)
        ActivityScenario.launch(EngineActivity::class.java).use { scenario ->
            awaitReady(TimeUnit.MINUTES.toMillis(3))
            compose.waitUntil(60_000) { compose.onAllNodesWithTag("engine-preparing", useUnmergedTree = true).fetchSemanticsNodes().isEmpty() }
            assertTrue("palette lists the installed skill", paletteShows(scenario, "ci-fixture-skill", expected = true))
            V130Shots.take("07-palette-with-skill")
            store.setEnabled("ci-fixture-skill", false)
            reload(scenario)
            assertTrue("palette drops the disabled skill", !paletteShows(scenario, "ci-fixture-skill", expected = false))
            store.remove("ci-fixture-skill")
        }
    }

    private fun webView(scenario: ActivityScenario<EngineActivity>): WebView {
        val ref = AtomicReference<WebView>()
        scenario.onActivity { activity -> ref.set(findWebView(activity.window.decorView)) }
        return checkNotNull(ref.get())
    }

    private fun findWebView(view: android.view.View): WebView? {
        if (view is WebView) return view
        if (view is android.view.ViewGroup) for (i in 0 until view.childCount) findWebView(view.getChildAt(i))?.let { return it }
        return null
    }

    private fun reload(scenario: ActivityScenario<EngineActivity>) {
        scenario.onActivity { findWebView(it.window.decorView)?.reload() }
        Thread.sleep(6000)
    }

    private fun js(scenario: ActivityScenario<EngineActivity>, script: String): String {
        val web = webView(scenario)
        val latch = CountDownLatch(1)
        val out = AtomicReference("")
        scenario.onActivity { web.evaluateJavascript(script) { out.set(it ?: ""); latch.countDown() } }
        latch.await(10, TimeUnit.SECONDS)
        return out.get()
    }

    /** Type "/" into the composer and look for the name among the palette's entries; retried while the page settles. */
    private fun paletteShows(scenario: ActivityScenario<EngineActivity>, name: String, expected: Boolean): Boolean {
        val focus = "(function(){var e=document.querySelector('[data-composer-input],[contenteditable=true],textarea');" +
            "if(!e)return 'none';e.focus();return 'ok';})()"
        var seen = false
        for (attempt in 0 until 6) {
            if (js(scenario, focus).contains("ok")) {
                device.pressKeyCode(KeyEvent.KEYCODE_SLASH)
                Thread.sleep(2000)
                seen = js(scenario, "document.body.innerText.indexOf(${org.json.JSONObject.quote(name)})>=0").contains("true")
                device.pressKeyCode(KeyEvent.KEYCODE_DEL)
                if (seen == expected) return seen
            }
            Thread.sleep(3000)
        }
        return seen
    }
}
