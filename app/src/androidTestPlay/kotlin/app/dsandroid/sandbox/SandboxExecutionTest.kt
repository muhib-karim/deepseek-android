package app.dsandroid.sandbox

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.dsandroid.R
import app.dsandroid.core.sandbox.CodeLanguage
import app.dsandroid.core.sandbox.CodeRequest
import app.dsandroid.core.sandbox.PythonRuntime
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** The code sandbox (code_interpreter) in a real WebView worker: results, limits, Stop, and what code cannot reach. */
@RunWith(AndroidJUnit4::class)
class SandboxExecutionTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val asked = AtomicInteger()
    private val executor by lazy { WebViewCodeExecutor(app) { asked.incrementAndGet(); false } }

    private fun js(code: String) = runBlocking { executor.execute(CodeRequest(CodeLanguage.JAVASCRIPT, code)) }

    @Test fun javascriptReturnsFourAndPrints() {
        val result = js("console.log('two plus two'); return 2 + 2")
        assertFalse(result.stderr, result.failed)
        assertEquals("4", result.value)
        assertEquals("two plus two\n", result.stdout)
    }

    @Test fun anInfiniteLoopIsStoppedAtTheTimeLimitAndTheNextRunWorks() {
        val result = js("while (true) {}")
        assertTrue(result.timedOut)
        assertEquals("1", js("return 1").value)
    }

    @Test fun stopEndsARunningLoopAtOnce() = runBlocking {
        val job = launch(Dispatchers.Default) { executor.execute(CodeRequest(CodeLanguage.JAVASCRIPT, "while (true) {}")) }
        delay(1500)
        val started = System.nanoTime()
        job.cancelAndJoin()
        assertTrue("Stop took too long", (System.nanoTime() - started) / 1_000_000 < 5_000)
        assertEquals("1", executor.execute(CodeRequest(CodeLanguage.JAVASCRIPT, "return 1")).value)
    }

    @Test fun codeCannotReachTheNetworkOrTheAppsFiles() {
        for (code in listOf(
            "const r = await fetch('https://example.com/'); return r.status",
            "const x = new XMLHttpRequest(); x.open('GET', 'file:///data/data/app.dsandroid/shared_prefs/', false); x.send(); return x.responseText",
            "return typeof Android + typeof indexedDB + typeof WebSocket",
        )) {
            val result = js(code)
            assertTrue(code, result.failed || result.value == "undefinedundefinedundefined")
            assertNotEquals(code, "200", result.value)
        }
    }

    @Test fun pythonAsksBeforeTheRuntimeDownloadAndADeclineIsReported() {
        File(app.filesDir, "python-runtime-${PythonRuntime.VERSION}").deleteRecursively()
        val result = runBlocking { executor.execute(CodeRequest(CodeLanguage.PYTHON, "2 + 2")) }
        assertEquals(1, asked.get())
        assertTrue(result.failed)
        assertEquals(app.getString(R.string.python_download_denied), result.stderr)
    }
}
