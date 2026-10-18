package app.dsandroid.core.network

import app.dsandroid.core.tools.WebFetchUrlPolicy
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.Proxy
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*

/** Bounded, cancellable HTTPS. No redirects, proxy, cookie jar or inherited provider credentials. */
object PublicHttps {
    private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).proxy(Proxy.NO_PROXY).dns(object : Dns {
            override fun lookup(hostname: String) = Dns.SYSTEM.lookup(hostname).also { addresses ->
                if (addresses.isEmpty() || addresses.any { !WebFetchUrlPolicy.isPublicAddress(it) }) throw IOException("Blocked host")
            }
        }).connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).callTimeout(30, TimeUnit.SECONDS).build()
    suspend fun text(request: Request, maxBytes: Int = 1024 * 1024): String {
        require(WebFetchUrlPolicy.isAllowed(request.url.toString()) && maxBytes in 1..2 * 1024 * 1024)
        val call = client.newCall(request)
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(e) }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        val value = response.use {
                            require(it.isSuccessful) { "Request refused; no redirects are followed" }
                            val body = requireNotNull(it.body)
                            require(body.contentLength() <= maxBytes)
                            val out = ByteArrayOutputStream()
                            body.byteStream().use { input ->
                                val buffer = ByteArray(8192)
                                while (true) {
                                    if (!continuation.isActive) throw IOException("Cancelled")
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    require(out.size() + count <= maxBytes)
                                    out.write(buffer, 0, count)
                                }
                            }
                            out.toByteArray().toString(body.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8)
                        }
                        if (continuation.isActive) continuation.resume(value)
                    } catch (error: Exception) { if (continuation.isActive) continuation.resumeWithException(error) }
                }
            })
        }
    }
}
