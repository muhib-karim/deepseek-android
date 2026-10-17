package app.dsandroid.sandbox

import android.content.Context
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
            if (python && !runtime.load()) return@withLock CodeExecution(stderr = context.getString(R.string.python_download_denied), failed = true)
            val key = "result" + UUID.randomUUID().toString().replace("-", "")
            val document = withContext(Dispatchers.Default) {
                if (python) CodeSandboxDocument.python(request.code, key)
                else CodeSandboxDocument.javascript(request.code, key)
            }
            withContext(Dispatchers.Main.immediate) {
                var rendererFailed = false
                val view = SandboxWebViews.create(context, javaScriptEnabled = true,
                    cachedResource = if (python) runtime::resource else null, onFailure = { rendererFailed = true })
                try {
                    withTimeoutOrNull(CodeLimits.WALL_TIME_MS) {
                        view.loadDataWithBaseURL(null, document, "text/html", "UTF-8", null)
                        var result: CodeExecution? = null
                        while (result == null) {
                            ensureActive()
                            if (rendererFailed) return@withTimeoutOrNull CodeExecution(failed = true,
                                stderr = context.getString(R.string.interpreter_failed))
                            val raw = suspendCancellableCoroutine<String> { reply ->
                                view.evaluateJavascript("typeof window.$key === 'function' ? window.$key() : null") {
                                    reply.resume(it ?: "null")
                                }
                            }
                            result = CodeResultParser.parse(raw)
                            if (result == null) delay(50)
                        }
                        result
                    } ?: CodeExecution(timedOut = true, failed = true)
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
}
