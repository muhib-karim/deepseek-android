package app.dsandroid.deepseek

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Message
import android.webkit.*
import app.dsandroid.core.deepseek.*
import app.dsandroid.BuildConfig
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import kotlin.coroutines.resume

/** Owns a single authenticated page. No addJavascriptInterface, arbitrary script or token injection. */
@SuppressLint("SetJavaScriptEnabled")
class DeepSeekWebView(
    context: Context,
    private val loginUrl: String = "${DeepSeekOrigin.URL}/sign_in",
    private val notice: (String) -> Unit,
) {
    init {
        require(DeepSeekOrigin.isTrusted(loginUrl) || isDebugLoopback(loginUrl)) { "Unsupported login origin." }
    }
    private fun isDebugLoopback(url: String): Boolean {
        val uri = Uri.parse(url)
        return BuildConfig.DEBUG && uri.scheme in setOf("http", "https") &&
            uri.host in setOf("localhost", "127.0.0.1", "[::1]", "::1") && uri.userInfo == null
    }
    private fun isLoginPage(url: String?): Boolean {
        if (DeepSeekOrigin.isTrusted(url)) return true
        if (url == null || !isDebugLoopback(loginUrl)) return false
        val target = Uri.parse(url)
        val origin = Uri.parse(loginUrl)
        return target.scheme == origin.scheme && target.host == origin.host && target.port == origin.port && target.userInfo == null
    }
    private var loaded = false
    private var navigationFailed = false
    private var rendererGone = false
    private var destroyed = false
    private var generation = 0
    private val solverLock = Mutex()
    val view = WebView(context).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        settings.javaScriptCanOpenWindowsAutomatically = false
        settings.setSupportMultipleWindows(true)
        settings.mediaPlaybackRequiresUserGesture = true
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
        webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean = true
            override fun onPermissionRequest(request: PermissionRequest?) { request?.deny() }
            override fun onCreateWindow(view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message?): Boolean {
                notice(SSO_NOTICE); return false
            }
        }
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                if (isGoogle(uri)) {
                    view.stopLoading(); notice(SSO_NOTICE); return true
                }
                val allowed = isLoginPage(uri.toString())
                if (!allowed && request.isForMainFrame) notice("This login destination is not supported. Stay on chat.deepseek.com or use another DeepSeek sign-in method.")
                return !allowed
            }
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest): WebResourceResponse? {
                // Also block embedded Google frames; never hand this session to an external browser.
                if (isGoogle(request.url)) {
                    view?.post { notice(SSO_NOTICE) }
                    return WebResourceResponse("text/plain", "UTF-8", "".byteInputStream())
                }
                val debugFixture = isDebugLoopback(loginUrl) && isLoginPage(request.url.toString())
                return if (request.url.scheme == "https" || debugFixture) null
                    else WebResourceResponse("text/plain", "UTF-8", "".byteInputStream())
            }
            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                loaded = false; navigationFailed = false; generation++
                if (url != null && isGoogle(Uri.parse(url))) {
                    view.stopLoading(); navigationFailed = true; notice(SSO_NOTICE)
                } else if (url != null && url != "about:blank" && !isLoginPage(url)) {
                    view.stopLoading(); navigationFailed = true; notice("Unsupported login destination blocked.")
                }
            }
            override fun onPageFinished(view: WebView, url: String?) {
                loaded = !navigationFailed && DeepSeekOrigin.isTrusted(url) && DeepSeekOrigin.isTrusted(view.url)
                if (url != null && isGoogle(Uri.parse(url))) notice(SSO_NOTICE)
                if (loaded) view.evaluateJavascript(HIDE_GOOGLE_JS, null)
                CookieManager.getInstance().flush()
            }
            override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler, error: SslError?) {
                handler.cancel(); loaded = false; navigationFailed = true; notice("DeepSeek's secure connection could not be verified. Login was stopped.")
            }
            override fun onReceivedError(view: WebView?, request: WebResourceRequest, error: WebResourceError?) {
                if (request.isForMainFrame) { loaded = false; navigationFailed = true; notice("The DeepSeek login page could not load. Check your connection and try again.") }
            }
            override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest, errorResponse: WebResourceResponse?) {
                if (request.isForMainFrame) {
                    loaded = false; navigationFailed = true
                    notice(if (isGoogle(request.url)) SSO_NOTICE else "DeepSeek refused this page request. Login or the web protocol may require an app update.")
                }
            }
            override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                loaded = false; rendererGone = true
                (view?.parent as? android.view.ViewGroup)?.removeView(view)
                view?.destroy()
                destroyed = true
                notice("The login browser stopped. Close and reopen the app to try again.")
                return true
            }
        }
    }

    fun openLogin() {
        if (destroyed) { notice("Reopen the app to restart the login browser."); return }
        loaded = false; navigationFailed = false
        view.loadUrl(loginUrl)
    }

    suspend fun captureSession(): SessionToken? = withContext(Dispatchers.Main.immediate) {
        if (!loaded || destroyed || !DeepSeekOrigin.isTrusted(view.url)) return@withContext null
        val epoch = generation
        val result = withTimeoutOrNull(2000) {
            evaluate("""(() => { if (location.origin !== "https://chat.deepseek.com") return null; return localStorage.getItem("userToken"); })()""")
        }
        if (epoch != generation || !DeepSeekOrigin.isTrusted(view.url)) return@withContext null
        val token = SessionToken.fromJavascript(result) ?: return@withContext null
        if (token.isExpired(System.currentTimeMillis())) return@withContext null
        token
    }

    suspend fun solve(challenge: PowChallenge): Long = withContext(Dispatchers.Main.immediate) {
        solverLock.withLock {
            try {
                withTimeout(30_000) {
                    if (destroyed) throw UpdateRequired()
                    if (!loaded || !DeepSeekOrigin.isTrusted(view.url)) {
                        loaded = false; navigationFailed = false
                        view.loadUrl(DeepSeekOrigin.URL)
                        while (!loaded) { if (destroyed || rendererGone || navigationFailed) throw UpdateRequired(); delay(100) }
                    }
                    val epoch = generation
                    val key = "__unofficialPow" + UUID.randomUUID().toString().replace("-", "")
                    val slot = JsonPrimitive(key).toString()
                    // A same-origin blob Worker imports the current public official worker at runtime.
                    // Only the challenge goes to the worker; neither token nor cookie enters this script.
                    val bootstrap = JsonPrimitive("importScripts(${JsonPrimitive(PowChallenge.WORKER_URL)});").toString()
                    try {
                        val started = evaluate("""
                            (() => {
                              if (location.origin !== "https://chat.deepseek.com") return false;
                              const state = {status: "pending"}; window[$slot] = state;
                              try {
                                const url = URL.createObjectURL(new Blob([$bootstrap], {type: "application/javascript"}));
                                state.url = url; const worker = new Worker(url); state.worker = worker;
                                worker.onerror = () => { state.status = "error"; };
                                worker.onmessage = event => {
                                  const data = event.data;
                                  if (data && data.type === "pow-answer" && data.answer && Number.isSafeInteger(data.answer.answer)) {
                                    state.answer = data.answer.answer; state.status = "done";
                                  } else { state.status = "error"; }
                                };
                                worker.postMessage(${challenge.workerMessage()});
                              } catch (_) { state.status = "error"; }
                              return true;
                            })()
                        """.trimIndent())
                        if (started != "true") throw UpdateRequired()
                        while (true) {
                            delay(100)
                            if (generation != epoch || !DeepSeekOrigin.isTrusted(view.url)) throw UpdateRequired()
                            val raw = evaluate("""(() => { if (location.origin !== "https://chat.deepseek.com") return null; const s = window[$slot]; return s ? JSON.stringify({status:s.status,answer:s.answer}) : null; })()""")
                            val text = (Json.parseToJsonElement(raw ?: "null") as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw UpdateRequired()
                            val result = Json.parseToJsonElement(text).jsonObject
                            when (result["status"]?.jsonPrimitive?.content) {
                                "done" -> {
                                    val answer = result["answer"]?.jsonPrimitive?.longOrNull ?: throw UpdateRequired()
                                    challenge.header(answer) // Bounds/algorithm validation before accepting the worker result.
                                    return@withTimeout answer
                                }
                                "pending" -> Unit
                                else -> throw UpdateRequired()
                            }
                        }
                        @Suppress("UNREACHABLE_CODE")
                        throw UpdateRequired()
                    } finally {
                        withContext(NonCancellable) {
                            if (!destroyed && generation == epoch && DeepSeekOrigin.isTrusted(view.url)) {
                                withTimeoutOrNull(1000) {
                                    evaluate("""(() => { if (location.origin !== "https://chat.deepseek.com") return; const s = window[$slot]; if(s) { if(s.worker) s.worker.terminate(); if(s.url) URL.revokeObjectURL(s.url); delete window[$slot]; } })()""")
                                }
                            }
                        }
                    }
                }
            } catch (_: TimeoutCancellationException) { throw UpdateRequired() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { throw UpdateRequired() }
        }
    }
    fun stopForLogout() { if (!destroyed) { view.stopLoading(); view.loadUrl("about:blank"); view.clearHistory() }; loaded = false; generation++ }
    fun destroy() { if (!destroyed) { view.stopLoading(); view.destroy() }; destroyed = true; loaded = false }
    private suspend fun evaluate(script: String): String? = suspendCancellableCoroutine { continuation ->
        if (destroyed) { continuation.resume(null); return@suspendCancellableCoroutine }
        view.evaluateJavascript(script) { result -> if (continuation.isActive) continuation.resume(result) }
    }
    private fun isGoogle(uri: Uri) = uri.scheme == "https" && uri.host == "accounts.google.com" && uri.userInfo == null && uri.port in setOf(-1, 443)
    companion object {
        /** Google OAuth cannot hand a session back to an app, so its button is hidden on the sign-in page instead of left as a dead end. */
        const val HIDE_GOOGLE_JS = """(function(){
  function hide(){
    var nodes = document.querySelectorAll(button,a,[role=button],div[class*=button],div[class*=btn]);
    for (var i = 0; i < nodes.length; i++) {
      var n = nodes[i];
      var t = (n.innerText || n.textContent || ).trim().toLowerCase();
      if (t.length < 40 && t.indexOf(google) !== -1) { n.style.setProperty(display,none,important); n.setAttribute(aria-hidden,true); }
    }
  }
  hide();
  if (!window.__dsHideGoogle) { window.__dsHideGoogle = new MutationObserver(hide); window.__dsHideGoogle.observe(document.documentElement, {childList:true, subtree:true}); }
})();"""
        const val SSO_NOTICE = "Google sign-in isn't allowed inside apps. Use phone number or email on this page."
    }
}
