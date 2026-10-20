package app.dsandroid.sandbox

import android.content.Context
import android.webkit.WebResourceResponse
import app.dsandroid.core.sandbox.PythonRuntime
import app.dsandroid.core.tools.WebFetchUrlPolicy
import java.io.File
import java.io.IOException
import java.net.Proxy
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.*

/**
 * A complete private cache avoids future downloads (the sandbox then works offline). Interrupted downloads and any file
 * whose SHA-256 differs from its pin (PythonRuntime.SHA256) are never accepted; a cache that fails the check is deleted.
 */
internal class PythonRuntimeLoader(context: Context, private val approve: suspend () -> Boolean) {
    private val root = File(context.filesDir, "python-runtime-${PythonRuntime.VERSION}")
    private val mutex = Mutex()
    /** The cache was hashed and matched the pins in this process; it is private storage, so once per process is enough. */
    @Volatile private var verified = false
    private val http = OkHttpClient.Builder().proxy(Proxy.NO_PROXY)
        .followRedirects(false).followSslRedirects(false)
        .dns(object : Dns {
            override fun lookup(hostname: String): List<java.net.InetAddress> {
                require(hostname == "cdn.jsdelivr.net")
                return Dns.SYSTEM.lookup(hostname).also { addresses ->
                    if (addresses.isEmpty() || addresses.any { !WebFetchUrlPolicy.isPublicAddress(it) }) throw IOException("Blocked host")
                }
            }
        }).connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).callTimeout(90, TimeUnit.SECONDS).build()

    suspend fun load(): Boolean = mutex.withLock {
        if (withContext(Dispatchers.IO) { cacheReady() && verifyCache() }) return@withLock true
        if (!approve()) return@withLock false
        withTimeout(120_000) {
            withContext(Dispatchers.IO) {
                // Recover disk space left by a killed download; these paths are private
                // runtime staging directories, never user artifacts or attachments.
                root.parentFile?.listFiles()?.filter {
                    it.isDirectory && Regex("python-download-[a-f0-9-]{36}").matches(it.name)
                }?.forEach { check(it.deleteRecursively()) }
                val staging = File(root.parentFile, "python-download-${UUID.randomUUID()}")
                try {
                    check(staging.mkdirs())
                    var remaining = PythonRuntime.MAX_BYTES
                    for (name in PythonRuntime.files) {
                        remaining -= download(PythonRuntime.BASE_URL + name, remaining, File(staging, name))
                    }
                    currentCoroutineContext().ensureActive()
                    File(staging, "complete").outputStream().use { it.write(PythonRuntime.VERSION.toByteArray()); it.fd.sync() }
                    if (root.exists()) check(root.deleteRecursively())
                    check(staging.renameTo(root))
                    check(cacheReady())
                    verified = true
                    true
                } finally { staging.deleteRecursively() }
            }
        }
    }

    /** Hashes the cached files against the pins; a mismatch (a damaged cache) deletes the cache so it is downloaded again. */
    private fun verifyCache(): Boolean {
        if (verified) return true
        val intact = PythonRuntime.files.all { name -> PythonRuntime.matchesPin(name, sha256(File(root, name))) }
        if (!intact) root.deleteRecursively() else verified = true
        return intact
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(65536)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun cacheReady(): Boolean {
        if (!File(root, "complete").isFile) return false
        var remaining = PythonRuntime.MAX_BYTES.toLong()
        return runCatching {
            PythonRuntime.files.forEach { name ->
                val file = File(root, name)
                require(file.isFile && file.length() in 1..remaining)
                remaining -= file.length()
            }
            true
        }.getOrDefault(false)
    }

    /** WebView owns and closes this stream. The URL is synthetic and cannot reach a socket. */
    fun resource(url: String): WebResourceResponse? {
        val name = PythonRuntime.cachedFile(url) ?: return null
        return runCatching {
            val file = File(root, name)
            require(file.isFile && file.length() in 1..PythonRuntime.MAX_BYTES.toLong())
            val mime = when {
                name.endsWith(".js") -> "text/javascript"
                name.endsWith(".wasm") -> "application/wasm"
                name.endsWith(".json") -> "application/json"
                else -> "application/octet-stream"
            }
            WebResourceResponse(mime, null, 200, "OK", mapOf(
                "Access-Control-Allow-Origin" to "*", "Cache-Control" to "no-store",
                "Cross-Origin-Resource-Policy" to "cross-origin"
            ), file.inputStream())
        }.getOrNull()
    }

    private suspend fun download(url: String, maximum: Int, target: File): Int = suspendCancellableCoroutine { continuation ->
        require(PythonRuntime.isAllowedUrl(url))
        val call = http.newCall(Request.Builder().url(url).get().build())
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { continuation.resumeWithException(e) }
            override fun onResponse(call: Call, response: Response) {
                val result = runCatching {
                    response.use {
                        require(it.isSuccessful && it.request.url.toString() == url)
                        val body = requireNotNull(it.body)
                        require(body.contentLength() <= maximum)
                        var total = 0
                        val digest = MessageDigest.getInstance("SHA-256")
                        target.outputStream().use { output -> body.byteStream().use { input ->
                            val buffer = ByteArray(8192)
                            while (true) {
                                if (!continuation.isActive) throw IOException("Download cancelled")
                                val count = input.read(buffer)
                                if (count < 0) break
                                require(total + count <= maximum)
                                output.write(buffer, 0, count)
                                digest.update(buffer, 0, count)
                                total += count
                            }
                        }; output.fd.sync() }
                        require(total > 0)
                        val actual = digest.digest().joinToString("") { b -> "%02x".format(b.toInt() and 0xff) }
                        if (!PythonRuntime.matchesPin(target.name, actual)) throw IOException("${target.name} does not match its pinned SHA-256")
                        total
                    }
                }
                result.fold({ continuation.resume(it) }, { continuation.resumeWithException(it) })
            }
        })
    }
}
