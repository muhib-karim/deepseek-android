package app.dsandroid.sandbox

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import android.webkit.*
import app.dsandroid.BuildConfig
import app.dsandroid.core.sandbox.CodeSandboxDocument
import app.dsandroid.core.sandbox.SandboxPreviewDocument
import app.dsandroid.core.tools.WebFetchUrlPolicy
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.Proxy
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Disposable views: previews have an opaque origin; code runs on the reserved https://sandbox.invalid origin
 * (CodeSandboxDocument.BASE_URL), which never reaches a socket. No Android bridge, cookies, files, storage or navigation.
 */
object SandboxWebViews {
    private val previewHttp = OkHttpClient.Builder().proxy(Proxy.NO_PROXY)
        .followRedirects(false).followSslRedirects(false)
        .dns(object : Dns {
            override fun lookup(hostname: String): List<java.net.InetAddress> =
                Dns.SYSTEM.lookup(hostname).also { addresses ->
                    if (addresses.isEmpty() || addresses.any { !WebFetchUrlPolicy.isPublicAddress(it) }) throw IOException("Blocked host")
                }
        })
        .connectTimeout(5, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.SECONDS).build()

    @SuppressLint("SetJavaScriptEnabled") // HTML and trusted worker bootstrap only; no native bridge.
    @Suppress("DEPRECATION")
    fun create(context: Context, javaScriptEnabled: Boolean, approvedNetwork: Boolean = false,
        cachedResource: ((String) -> WebResourceResponse?)? = null,
        onFailure: () -> Unit = {}, onConsole: ((String) -> Unit)? = null,
        onPageEvent: ((String) -> Unit)? = null, sandboxPage: String? = null): WebView = WebView(context).apply {
        settings.javaScriptEnabled = javaScriptEnabled
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.allowFileAccessFromFileURLs = false
        settings.allowUniversalAccessFromFileURLs = false
        settings.domStorageEnabled = false
        settings.databaseEnabled = false
        settings.setGeolocationEnabled(false)
        settings.javaScriptCanOpenWindowsAutomatically = false
        settings.setSupportMultipleWindows(false)
        settings.mediaPlaybackRequiresUserGesture = true
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        settings.cacheMode = WebSettings.LOAD_NO_CACHE
        // Cached Python uses synthetic HTTPS requests: every one is intercepted below.
        // The code page itself is served the same way (a real https origin, so its blob worker is same-origin).
        settings.blockNetworkLoads = !approvedNetwork && cachedResource == null && sandboxPage == null
        settings.blockNetworkImage = !approvedNetwork && cachedResource == null && sandboxPage == null
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
        isSaveEnabled = false
        setDownloadListener { _, _, _, _, _ -> } // Exports belong to the explicit native Save action.
        val requests = AtomicInteger()
        val bytes = AtomicInteger()
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = true
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean = true
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                onPageEvent?.invoke("started ${url?.take(40)}")
                if (url != null && url != "about:blank" && url != CodeSandboxDocument.BASE_URL && !url.startsWith("data:text/html")) view?.stopLoading()
            }
            override fun onPageFinished(view: WebView?, url: String?) { onPageEvent?.invoke("finished ${url?.take(40)}") }
            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                if (request?.isForMainFrame == true) onPageEvent?.invoke("error ${error?.errorCode} ${error?.description}")
            }
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse {
                if (sandboxPage != null && request != null && request.isForMainFrame) {
                    return if (request.method == "GET" && request.url.toString() == CodeSandboxDocument.BASE_URL)
                        WebResourceResponse("text/html", "UTF-8", 200, "OK", mapOf("Cache-Control" to "no-store"),
                            ByteArrayInputStream(sandboxPage.toByteArray(Charsets.UTF_8)))
                    else blocked()
                }
                if (cachedResource != null) {
                    return if (request != null && !request.isForMainFrame && request.method == "GET")
                        cachedResource(request.url.toString()) ?: blocked() else blocked()
                }
                if (!approvedNetwork || request == null || request.isForMainFrame || request.method != "GET" ||
                    requests.incrementAndGet() > 40 || bytes.get() >= 8 * 1024 * 1024) return blocked()
                val url = request.url.toString()
                if (!WebFetchUrlPolicy.isAllowed(url)) return blocked()
                return try {
                    // No page headers, cookies, credentials, redirect following, or referrer are forwarded.
                    previewHttp.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
                        if (!response.isSuccessful) return blocked()
                        val body = response.body ?: return blocked()
                        val output = ByteArrayOutputStream()
                        body.byteStream().use { input ->
                            val buffer = ByteArray(8192)
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                if (output.size() + count > 2 * 1024 * 1024 || bytes.addAndGet(count) > 8 * 1024 * 1024) return blocked()
                                output.write(buffer, 0, count)
                            }
                        }
                        val type = body.contentType()
                        WebResourceResponse(type?.let { "${it.type}/${it.subtype}" } ?: "application/octet-stream",
                            type?.charset()?.name() ?: "UTF-8", ByteArrayInputStream(output.toByteArray()))
                    }
                } catch (_: Exception) { blocked() }
            }
            override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                onFailure()
                return true // The owner disposes the failed view; do not crash the app.
            }
        }
        webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest?) { request?.deny() }
            // Page-level messages (the trusted bootstrap's own errors); code output goes through the worker's captured console.
            override fun onConsoleMessage(message: ConsoleMessage?): Boolean {
                if (message != null) {
                    onConsole?.invoke("${message.messageLevel()}: ${message.message()}")
                    if (BuildConfig.DEBUG) Log.w("DsSandbox", "${message.messageLevel()} ${message.message()} (line ${message.lineNumber()})")
                }
                return true
            }
            override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: GeolocationPermissions.Callback?) {
                callback?.invoke(origin, false, false)
            }
            override fun onJsAlert(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean { result?.cancel(); return true }
            override fun onJsConfirm(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean { result?.cancel(); return true }
            override fun onJsPrompt(view: WebView?, url: String?, message: String?, defaultValue: String?, result: JsPromptResult?): Boolean { result?.cancel(); return true }
            override fun onShowFileChooser(view: WebView?, callback: ValueCallback<Array<android.net.Uri>>?, params: FileChooserParams?): Boolean {
                callback?.onReceiveValue(null); return true
            }
        }
    }

    fun loadPreview(view: WebView, content: String, javaScriptEnabled: Boolean = false, approvedNetwork: Boolean = false, localProject: Boolean = false) {
        val document = SandboxPreviewDocument.wrap(content, javaScriptEnabled, approvedNetwork, localProject)
        view.loadDataWithBaseURL(null, document, "text/html", "UTF-8", null)
    }

    private fun blocked() = WebResourceResponse("text/plain", "UTF-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))
}
