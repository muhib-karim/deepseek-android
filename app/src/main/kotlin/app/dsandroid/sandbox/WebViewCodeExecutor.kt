package app.dsandroid.sandbox

import android.content.Context
import android.os.SystemClock
import app.dsandroid.R
import app.dsandroid.core.sandbox.*
import java.util.UUID
import kotlin.coroutines.resume
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** JS-only result channel: native code polls a trusted page; no addJavascriptInterface exists. */
class WebViewCodeExecutor(context: Context, approvePythonDownload: suspend () -> Boolean) : CodeExecutor {
    private val context = context.applicationContext
    private val runtime = PythonRuntimeLoader(this.context, approvePythonDownload)
    private val mutex = Mutex()

    override suspend fun execute(request: CodeRequest): CodeExecution = mutex.withLock {
        try {
            val python = request.language == CodeLanguage.PYTHON
            if (python) {
                // The real reason goes to stderr (and so to the model and the tool row), never only a generic message.
                val ready = try { runtime.load() }
                    catch (_: TimeoutCancellationException) { return@withLock CodeExecution(stderr = "The Python runtime download did not finish within 2 minutes.", failed = true) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) {
                        return@withLock CodeExecution(stderr = "The Python runtime could not be prepared: ${error.message ?: error.javaClass.simpleName}".take(500), failed = true)
                    }
                if (!ready) return@withLock CodeExecution(stderr = context.getString(R.string.python_download_denied), failed = true)
            }
            val key = "result" + UUID.randomUUID().toString().replace("-", "")
            val page = withContext(Dispatchers.Default) {
                if (python) CodeSandboxDocument.pythonPage(request.code, key)
                else CodeSandboxDocument.javascriptPage(request.code, key)
            }
            withContext(Dispatchers.Main.immediate) {
                var rendererFailed = false
                var started = false
                val messages = ArrayList<String>()
                val events = ArrayList<String>()
                var finished = false
                var unanswered = 0
                var bootstrapped = false
                val view = SandboxWebViews.create(context, javaScriptEnabled = true,
                    cachedResource = if (python) runtime::resource else null, onFailure = { rendererFailed = true },
                    onConsole = { line -> if (messages.size < 8) messages += line.take(300) },
                    onPageEvent = { event -> if (event.startsWith("finished")) finished = true; if (events.size < 8) events += event })
                try {
                    withTimeoutOrNull(CodeLimits.WALL_TIME_MS) {
                        // A real https origin (never resolved, every request intercepted), so the blob worker is same-origin.
                        view.loadDataWithBaseURL(CodeSandboxDocument.BASE_URL, page.shell, "text/html", "UTF-8", null)
                        val loadStarted = SystemClock.uptimeMillis()
                        var result: CodeExecution? = null
                        while (result == null) {
                            ensureActive()
                            if (rendererFailed) return@withTimeoutOrNull CodeExecution(failed = true,
                                stderr = context.getString(R.string.interpreter_failed) + " (the WebView renderer stopped)")
                            // Poll only once the page has committed: a script sent to the outgoing document while the
                            // navigation swaps frames never answers. Each poll is bounded, so a lost answer is just retried.
                            if (!finished && !started && SystemClock.uptimeMillis() - loadStarted < 3_000) { delay(50); continue }
                            // The trusted bootstrap is run by the host once the (script-free) shell has loaded, rather than
                            // inline, so the page cannot be up without it. It defines the poll function first.
                            if (!bootstrapped) {
                                bootstrapped = true
                                view.evaluateJavascript(page.bootstrap, null)
                            }
                            val raw = withTimeoutOrNull(POLL_TIMEOUT_MS) {
                                suspendCancellableCoroutine<String> { reply ->
                                    view.evaluateJavascript("typeof window.$key === 'function' ? window.$key() : null") {
                                        if (reply.isActive) reply.resume(it ?: "null")
                                    }
                                }
                            } ?: run { unanswered++; "null" }
                            if (CodeResultParser.isRunning(raw)) started = true else result = CodeResultParser.parse(raw)
                            if (result == null) delay(50)
                        }
                        result
                    } ?: CodeExecution(timedOut = true, failed = true, stderr = when {
                        // A page that never started is a sandbox fault, not slow code: say so, with what the page reported.
                        !started -> "The sandbox page did not start (page: " + events.joinToString("; ").ifEmpty { "no load events" } +
                            "; unanswered polls: $unanswered; probe: ${probe(view)})." +
                            if (messages.isEmpty()) "" else " Page messages:\n" + messages.joinToString("\n")
                        else -> messages.joinToString("\n")
                    })
                } finally {
                    // destroy also terminates workers, including infinite loops and pending promises.
                    view.stopLoading()
                    view.destroy()
                }
            }
        } catch (_: TimeoutCancellationException) { CodeExecution(stderr = context.getString(R.string.interpreter_failed), failed = true) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { CodeExecution(stderr = context.getString(R.string.interpreter_failed), failed = true) }
    }

    /** What the page looks like from the host when it never started: script support, document state and origin. */
    private suspend fun probe(view: android.webkit.WebView): String = withTimeoutOrNull(POLL_TIMEOUT_MS) {
        suspendCancellableCoroutine<String> { reply ->
            view.evaluateJavascript("[1 + 1, document.readyState, document.scripts.length, location.origin, typeof Worker].join(',')") {
                if (reply.isActive) reply.resume(it ?: "null")
            }
        }
    } ?: "no answer"

    private companion object { const val POLL_TIMEOUT_MS = 1_000L }
}
