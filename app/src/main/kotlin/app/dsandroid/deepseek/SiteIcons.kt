package app.dsandroid.deepseek

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import app.dsandroid.core.deepseek.WebCitation
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.CookieJar
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Small site icons for search steps ("Found N web pages", "Read N pages"), loaded only when a step is shown: DeepSeek's own
 * icon for the site (cdn.deepseek.com, named in the search result) or else the site's /favicon.ico. Plain HTTPS GETs with no
 * cookies or credentials, at most 64 KB, cached in memory and in the app's cache folder; a letter is shown when none loads.
 */
object SiteIcons {
    private const val MAX_BYTES = 64 * 1024
    private val memory = LruCache<String, Bitmap>(96)
    /** Hosts already tried without success in this process. */
    private val missing = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    private val http by lazy {
        OkHttpClient.Builder().cookieJar(CookieJar.NO_COOKIES).followSslRedirects(false)
            .connectTimeout(8, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS).callTimeout(12, TimeUnit.SECONDS).build()
    }

    fun host(url: String): String = url.toHttpUrlOrNull()?.host?.removePrefix("www.").orEmpty()

    suspend fun load(context: Context, page: WebCitation): Bitmap? {
        val host = host(page.url).takeIf { it.isNotEmpty() } ?: return null
        memory.get(host)?.let { return it }
        if (host in missing) return null
        return withContext(Dispatchers.IO) {
            val file = File(File(context.cacheDir, "site-icons"), digest(host))
            val cached = runCatching { if (file.isFile) BitmapFactory.decodeFile(file.path) else null }.getOrNull()
            val bitmap = cached ?: candidates(page).firstNotNullOfOrNull { url -> fetch(url)?.let { bytes ->
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.also {
                    runCatching { file.parentFile?.mkdirs(); file.writeBytes(bytes) }
                }
            } }
            if (bitmap == null) missing += host else memory.put(host, bitmap)
            bitmap
        }
    }

    private fun candidates(page: WebCitation): List<String> {
        val deepSeekIcon = page.icon.takeIf { it.toHttpUrlOrNull()?.let { url -> url.isHttps && url.host == "cdn.deepseek.com" } == true }
        val site = page.url.toHttpUrlOrNull()?.takeIf { it.isHttps }?.let { "https://${it.host}/favicon.ico" }
        return listOfNotNull(deepSeekIcon, site)
    }

    private fun fetch(url: String): ByteArray? = runCatching {
        http.newCall(Request.Builder().url(url).header("Accept", "image/*").build()).execute().use { response ->
            val body = response.body ?: return@use null
            if (!response.isSuccessful || !response.request.url.isHttps) return@use null
            val source = body.source()
            if (source.request(MAX_BYTES + 1L)) null else source.readByteArray()
        }
    }.getOrNull()

    private fun digest(host: String): String =
        MessageDigest.getInstance("SHA-256").digest(host.toByteArray()).joinToString("") { "%02x".format(it) }.take(32)
}
