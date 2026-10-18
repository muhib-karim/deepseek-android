package app.dsandroid.browser

import android.content.Context
import android.view.View
import android.webkit.WebView
import app.dsandroid.core.browser.*
import app.dsandroid.core.llm.LlmJson
import app.dsandroid.core.network.PublicHttps
import app.dsandroid.core.tools.WebFetchUrlPolicy
import app.dsandroid.core.tools.stringArg
import app.dsandroid.sandbox.SandboxWebViews
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import kotlin.coroutines.resume

class BrowserController(private val context: Context) : BrowserDriver {
    data class State(val url: String = "", val takeover: Boolean = false, val revision: Long = 0, val viewVersion: Long = 0)
    private val mutable = MutableStateFlow(State())
    val state = mutable.asStateFlow()
    override val url: String get() = mutable.value.url
    private val lock = Mutex()
    private var webView: WebView? = null
    private var ready = CompletableDeferred<Unit>()
    private val pages = ArrayDeque<Pair<String, String>>()
    private var html = ""

    @Suppress("DEPRECATION")
    fun view(): WebView = webView ?: SandboxWebViews.create(context, true, onFailure = { close() }).also { view ->
        // The shared sandbox denies every request, permission, popup and native navigation.
        view.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        view.settings.saveFormData = false
        // All editing/actions use the native takeover controls and their confirmation preview.
        // Touch remains disabled so WebView cannot start native form editing or navigation.
        view.setOnTouchListener { _, _ -> true }
        view.isFocusable = false
        view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        webView = view
    }

    fun takeOver(value: Boolean) { mutable.value = mutable.value.copy(takeover = value, revision = mutable.value.revision + 1) }

    override suspend fun preview(action: String, args: JsonObject): JsonObject = withContext(Dispatchers.Main.immediate) {
        check(!mutable.value.takeover)
        descriptor(action, args)
    }

    private suspend fun descriptor(action: String, args: JsonObject): JsonObject {
        val details = if (action in setOf("click", "type", "submit")) {
            val target = args["target"]?.jsonPrimitive?.int ?: error("Missing target")
            LlmJson.parseToJsonElement(js(BrowserScripts.inspect(target, if (action == "type") args.stringArg("text") else ""))).jsonObject
        } else buildJsonObject { args.forEach { (key, value) -> put(key, value) } }
        return buildJsonObject {
            put("action", action); put("page", url); put("revision", mutable.value.revision)
            put("details", details)
            if (action == "back") put("destination", pages.lastOrNull()?.first ?: error("No previous page"))
            if (action == "open_url") { require(WebFetchUrlPolicy.isAllowed(args.stringArg("url"))); put("destination", args.stringArg("url")) }
            if (action in setOf("click", "submit")) put("request", navigation(action, details))
        }
    }

    private fun navigation(action: String, details: JsonObject): JsonObject {
        val link = details["href"]!!.jsonPrimitive.content
        if (action == "click" && link.isNotBlank()) {
            val destination = requireNotNull(url.toHttpUrl().resolve(link)).toString()
            require(WebFetchUrlPolicy.isAllowed(destination))
            return buildJsonObject { put("url", destination); put("method", "get") }
        }
        require(details["tag"]!!.jsonPrimitive.content == "FORM" ||
            (details["tag"]!!.jsonPrimitive.content in setOf("BUTTON", "INPUT") && details["kind"]!!.jsonPrimitive.content == "submit"))
        val method = details["method"]!!.jsonPrimitive.content
        require(method in setOf("get", "post"))
        val destination = requireNotNull(url.toHttpUrl().resolve(details["action"]!!.jsonPrimitive.content)).toString()
        require(WebFetchUrlPolicy.isAllowed(destination))
        val fields = details["fields"]!!.jsonArray
        require(fields.size <= 100 && fields.toString().length <= 16_000)
        return buildJsonObject { put("url", destination); put("method", method); put("fields", fields) }
    }

    override suspend fun execute(action: String, args: JsonObject, approved: JsonObject?): String = lock.withLock {
        withContext(Dispatchers.Main.immediate) {
            check(!mutable.value.takeover)
            if (approved != null) check(descriptor(action, args) == approved) { "Page changed; approval expired" }
            when (action) {
                "open_url" -> load(args.stringArg("url"))
                "read_page" -> js(BrowserScripts.read)
                "scroll" -> { js(BrowserScripts.scroll(args.stringArg("direction") == "down")); "Scrolled" }
                "back" -> {
                    requireNotNull(approved)
                    val previous = pages.removeLast()
                    display(previous.first, previous.second)
                    "Opened ${previous.first}"
                }
                "type" -> {
                    requireNotNull(approved)
                    js(BrowserScripts.type(args["target"]!!.jsonPrimitive.int, args.stringArg("text")))
                    mutable.value = mutable.value.copy(revision = mutable.value.revision + 1)
                    "Entered text in the approved field"
                }
                "click", "submit" -> {
                    val request = requireNotNull(approved)["request"]!!.jsonObject
                    val fields = request["fields"]?.jsonArray.orEmpty()
                    val destination = request.stringArg("url").toHttpUrl()
                    if (request.stringArg("method") == "get") {
                        val builder = destination.newBuilder()
                        fields.forEach { pair -> builder.addQueryParameter(pair.jsonArray[0].jsonPrimitive.content, pair.jsonArray[1].jsonPrimitive.content) }
                        load(builder.build().toString())
                    } else {
                        val body = FormBody.Builder().apply { fields.forEach { pair -> add(pair.jsonArray[0].jsonPrimitive.content, pair.jsonArray[1].jsonPrimitive.content) } }.build()
                        load(destination.toString(), Request.Builder().url(destination).post(body).build())
                    }
                }
                else -> error("Unknown browser operation")
            }
        }
    }

    private suspend fun load(destination: String, request: Request = Request.Builder().url(destination).get().build()): String {
        require(WebFetchUrlPolicy.isAllowed(destination))
        val expected = mutable.value.revision
        val content = PublicHttps.text(request)
        check(expected == mutable.value.revision && !mutable.value.takeover)
        if (url.isNotEmpty()) { pages.addLast(url to html); if (pages.size > 10) pages.removeFirst() }
        display(destination, content)
        return "Opened $destination. Use read_page to inspect targets."
    }

    private suspend fun display(destination: String, content: String) {
        html = content
        mutable.value = mutable.value.copy(url = destination, revision = mutable.value.revision + 1)
        ready = CompletableDeferred()
        val pageId = java.util.UUID.randomUUID().toString()
        view().loadDataWithBaseURL(null, BrowserPolicy.document(content, pageId), "text/html", "UTF-8", null)
        // Poll through a fixed script; no page-owned callback or native JavaScript bridge.
        withTimeout(10_000) {
            while (rawJs("document.readyState === 'complete' && document.querySelector('meta[name=dsandroid-page]')?.content === ${JsonPrimitive(pageId)}") != "true") delay(50)
        }
        ready.complete(Unit)
    }
    private suspend fun js(script: String): String {
        withTimeout(10_000) { ready.await() }
        return rawJs(script)
    }
    private suspend fun rawJs(script: String): String = withTimeout(10_000) {
        suspendCancellableCoroutine { continuation ->
            view().evaluateJavascript(script) { value ->
                if (continuation.isActive) {
                    val result = runCatching { LlmJson.parseToJsonElement(value).jsonPrimitive.content }.getOrDefault("null")
                    continuation.resume(result)
                }
            }
        }.also { require(it != "null") { "Browser operation unavailable" } }
    }
    fun close() {
        webView?.let { (it.parent as? android.view.ViewGroup)?.removeView(it); it.stopLoading(); it.destroy() }
        webView = null; pages.clear(); html = ""; ready.cancel(); ready = CompletableDeferred()
        mutable.value = State(revision = mutable.value.revision + 1, viewVersion = mutable.value.viewVersion + 1)
    }
}
