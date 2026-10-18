package app.dsandroid.auth

import app.dsandroid.core.auth.LoopbackCallbackParser
import app.dsandroid.core.auth.OAuthState
import app.dsandroid.core.auth.Pkce
import app.dsandroid.core.llm.ClientIdentity
import app.dsandroid.data.AppSettings
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/** Configurable standard PKCE adapter. No undocumented provider endpoints are assumed. */
class AccountSignIn(private val identity: ClientIdentity, private val callbackSuccess: String, private val callbackFailure: String) {
    private val http = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS).build()

    suspend fun signIn(settings: AppSettings, openBrowser: (String) -> Unit, scope: String? = null): String {
        require(settings.accountConfigured) { "Account sign-in endpoint not configured; use an API key" }
        val authorize = endpoint(settings.authorizeUrl)
        val exchangeEndpoint = endpoint(settings.exchangeUrl)
        val verifier = Pkce.verifier()
        val state = OAuthState.create()
        val server = ServerSocket()
        try {
            withContext(Dispatchers.IO) {
                server.bind(InetSocketAddress(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), 0), 1)
                server.soTimeout = 1000
            }
            val redirect = "http://127.0.0.1:${server.localPort}/oauth/callback"
            val url = authorize.newBuilder()
                .setQueryParameter("response_type", "code").setQueryParameter("client_id", settings.clientId)
                .setQueryParameter("redirect_uri", redirect).setQueryParameter("state", state)
                .setQueryParameter("code_challenge", Pkce.challenge(verifier)).setQueryParameter("code_challenge_method", "S256")
                .apply { if (scope == null) setQueryParameter("login_source", "web") else setQueryParameter("scope", scope) }.build()
            withContext(Dispatchers.Main) { openBrowser(url.toString()) }
            val code = callback(server, state)
            val form = FormBody.Builder().add("grant_type", "authorization_code").add("code", code)
                .add("client_id", settings.clientId).add("redirect_uri", redirect).add("code_verifier", verifier).build()
            val request = Request.Builder().url(exchangeEndpoint).post(form)
            identity.headers().forEach { (name, value) -> request.header(name, value) }
            return if (scope == null) exchange(http.newCall(request.build())) else
                AccountTokenParser.parse(app.dsandroid.core.network.PublicHttps.text(request.build(), 64 * 1024))
        } finally { server.close() }
    }

    private fun endpoint(value: String): HttpUrl = value.toHttpUrl().also {
        require(it.isHttps && it.username.isEmpty() && it.password.isEmpty() && it.fragment == null) { "Account endpoints require HTTPS without embedded credentials" }
    }

    private suspend fun callback(server: ServerSocket, expectedState: String): String = suspendCancellableCoroutine { continuation ->
        val activeSocket = AtomicReference<Socket?>(null)
        continuation.invokeOnCancellation { runCatching { activeSocket.get()?.close() }; runCatching { server.close() } }
        Thread({
            try {
                val deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(3)
                while (continuation.isActive && System.nanoTime() < deadline) {
                    val socket = try { server.accept() } catch (_: SocketTimeoutException) { continue }
                    activeSocket.set(socket)
                    socket.use {
                        it.soTimeout = 2000
                        try {
                            val reader = it.getInputStream().bufferedReader(Charsets.US_ASCII)
                            val line = StringBuilder()
                            while (line.length < 8192) {
                                val char = reader.read()
                                if (char == -1 || char == '\n'.code) break
                                if (char != '\r'.code) line.append(char.toChar())
                            }
                            val result = runCatching { LoopbackCallbackParser.parse(line.toString(), expectedState) }
                            val message = if (result.isSuccess) callbackSuccess else callbackFailure
                            val status = if (result.isSuccess) "200 OK" else "400 Bad Request"
                            val body = message.toByteArray(Charsets.UTF_8)
                            val output = it.getOutputStream()
                            output.write(("HTTP/1.1 $status\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: ${body.size}\r\nConnection: close\r\nCache-Control: no-store\r\n\r\n").toByteArray(Charsets.US_ASCII))
                            output.write(body); output.flush()
                            if (result.isSuccess) { continuation.resume(result.getOrThrow()); return@Thread }
                            // A validated OAuth error should finish immediately, not wait for a timeout.
                            if (result.exceptionOrNull()?.message?.startsWith("Authorization declined") == true) throw IOException("Account authorization was declined; use an API key")
                        } catch (_: SocketTimeoutException) { /* Ignore unrelated local probes. */ }
                    }
                    activeSocket.set(null)
                }
                if (continuation.isActive) continuation.resumeWithException(IOException("Sign-in timed out; try again or use an API key"))
            } catch (error: Exception) {
                if (continuation.isActive) continuation.resumeWithException(error)
            } finally {
                runCatching { activeSocket.getAndSet(null)?.close() }
                runCatching { server.close() }
            }
        }, "dsandroid-oauth-callback").apply { isDaemon = true; start() }
    }

    private suspend fun exchange(call: Call): String = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { continuation.resumeWithException(e) }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val token = response.use {
                        if (!it.isSuccessful) throw IOException("Account exchange was refused; use an API key")
                        val body = it.body ?: throw IOException("Empty account exchange response")
                        val bytes = body.byteStream().use { stream ->
                            val output = java.io.ByteArrayOutputStream()
                            val buffer = ByteArray(4096)
                            while (true) {
                                val count = stream.read(buffer)
                                if (count == -1) break
                                require(output.size() + count <= 64 * 1024) { "Account response too large" }
                                output.write(buffer, 0, count)
                            }
                            output.toByteArray()
                        }
                        AccountTokenParser.parse(bytes.toString(Charsets.UTF_8))
                    }
                    continuation.resume(token)
                } catch (error: Exception) { continuation.resumeWithException(error) }
            }
        })
    }
}
