package app.dsandroid.sandbox

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.dsandroid.core.sandbox.CodeLanguage
import app.dsandroid.core.sandbox.CodeRequest
import app.dsandroid.core.sandbox.PythonRuntime
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * WASM Python in the code sandbox with the real Pyodide runtime from cdn.jsdelivr.net (needs the emulator's network): the
 * download is approved once and every file must match its pinned SHA-256; a new executor then reuses the cache without asking.
 */
@RunWith(AndroidJUnit4::class)
class SandboxPythonTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()

    @Test fun pinnedRuntimeRunsPythonAndIsReusedWithoutAskingAgain() {
        val cache = File(app.filesDir, "python-runtime-${PythonRuntime.VERSION}")
        cache.deleteRecursively()
        var approvals = 0
        val first = runBlocking { WebViewCodeExecutor(app) { approvals++; true }.execute(CodeRequest(CodeLanguage.PYTHON, "print('hi')\n2 + 2")) }
        assertEquals(1, approvals)
        assertFalse(first.stderr, first.failed)
        assertEquals("4", first.value)
        assertEquals("hi\n", first.stdout)
        assertTrue(PythonRuntime.files.all { File(cache, it).isFile })

        val again = runBlocking { WebViewCodeExecutor(app) { fail("The verified cache must not ask again"); false }.execute(CodeRequest(CodeLanguage.PYTHON, "6 * 7")) }
        assertFalse(again.stderr, again.failed)
        assertEquals("42", again.value)
    }

    @Test fun aDamagedCacheIsDiscardedAndAskedForAgain() {
        val cache = File(app.filesDir, "python-runtime-${PythonRuntime.VERSION}")
        cache.deleteRecursively()
        check(cache.mkdirs())
        PythonRuntime.files.forEach { File(cache, it).writeText("not the runtime") }
        File(cache, "complete").writeText(PythonRuntime.VERSION)
        var approvals = 0
        val result = runBlocking { WebViewCodeExecutor(app) { approvals++; false }.execute(CodeRequest(CodeLanguage.PYTHON, "2 + 2")) }
        assertEquals("A cache that fails the pins is not used", 1, approvals)
        assertTrue(result.failed)
        assertFalse(cache.exists())
    }
}
