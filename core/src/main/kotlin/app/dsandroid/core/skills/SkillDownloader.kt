package app.dsandroid.core.skills

import app.dsandroid.core.tools.WebFetchUrlPolicy
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/** Fetches a skill archive the user asked for: public HTTPS only (redirects too), at most 5 MB. */
class SkillDownloader(client: OkHttpClient? = null) {
    private val http = (client ?: OkHttpClient.Builder().proxy(Proxy.NO_PROXY).dns(object : Dns {
        override fun lookup(hostname: String): List<InetAddress> = Dns.SYSTEM.lookup(hostname).also { addresses ->
            if (addresses.isEmpty() || addresses.any { !WebFetchUrlPolicy.isPublicAddress(it) }) throw IOException("Private network destination blocked")
        }
    }).build()).newBuilder().followRedirects(true).followSslRedirects(false)
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).callTimeout(60, TimeUnit.SECONDS).build()

    suspend fun download(url: String, requireHttps: Boolean = true): ByteArray {
        require(!requireHttps || WebFetchUrlPolicy.isAllowed(url)) { "Use a public https:// link to a skill .zip" }
        return suspendCancellableCoroutine { continuation ->
            val call = http.newCall(Request.Builder().url(url).header("Accept", "application/zip, application/octet-stream").build())
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { continuation.resumeWithException(e) }
                override fun onResponse(call: Call, response: Response) {
                    val result = runCatching {
                        response.use {
                            if (!it.isSuccessful) throw IOException("The skill download returned HTTP ${it.code}")
                            if (requireHttps && !it.request.url.isHttps) throw IOException("The skill download left HTTPS")
                            val body = it.body ?: throw IOException("Empty download")
                            if (body.contentLength() > SkillPackReader.MAX_ARCHIVE_BYTES) throw IOException("The skill archive exceeds 5 MB")
                            val output = ByteArrayOutputStream()
                            body.byteStream().use { input ->
                                val buffer = ByteArray(8192)
                                while (true) {
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    if (output.size() + count > SkillPackReader.MAX_ARCHIVE_BYTES) throw IOException("The skill archive exceeds 5 MB")
                                    output.write(buffer, 0, count)
                                }
                            }
                            output.toByteArray()
                        }
                    }
                    result.fold({ continuation.resume(it) }, { continuation.resumeWithException(it) })
                }
            })
        }
    }
}
