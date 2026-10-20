package app.dsandroid.core.accounts

import app.dsandroid.core.llm.ClientIdentity
import app.dsandroid.core.llm.ErrorExplainer
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/** Amounts stay strings, exactly as DeepSeek returns them. */
@Serializable
data class CurrencyBalance(val currency: String, val total: String, val granted: String = "", val toppedUp: String = "")

/** One balance check: what was read (or the plain-English failure) and when. */
@Serializable
data class BalanceReading(val readAt: Long, val available: Boolean? = null, val balances: List<CurrencyBalance> = emptyList(), val error: String? = null)

/** GET /user/balance (DeepSeek API docs): `is_available` and `balance_infos[]` with currency and string amounts. */
object BalanceParser {
    private val amount = Regex("-?\\d{1,15}(\\.\\d{1,8})?")
    private val currency = Regex("[A-Z]{3}")

    fun parse(body: String, readAt: Long): BalanceReading {
        val root = Json.parseToJsonElement(body) as? JsonObject ?: throw IllegalArgumentException("Not a balance object")
        val infos = root["balance_infos"] as? JsonArray ?: throw IllegalArgumentException("No balance_infos")
        val balances = infos.take(8).map { element ->
            val item = element as? JsonObject ?: throw IllegalArgumentException("Invalid balance entry")
            fun text(key: String) = (item[key] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
            val code = text("currency").also { require(it.matches(currency)) { "Invalid currency" } }
            val total = text("total_balance").also { require(it.matches(amount)) { "Invalid amount" } }
            CurrencyBalance(code, total, text("granted_balance").takeIf { it.matches(amount) }.orEmpty(),
                text("topped_up_balance").takeIf { it.matches(amount) }.orEmpty())
        }
        return BalanceReading(readAt, (root["is_available"] as? JsonPrimitive)?.booleanOrNull, balances)
    }
}

/**
 * Reads the balance of one API key. The key goes only to the DeepSeek API origin it belongs to; redirects are
 * not followed and failures come back as a reading with an error rather than an exception.
 */
class DeepSeekBalanceClient(baseUrl: String = DEFAULT_BASE_URL, private val identity: ClientIdentity, client: OkHttpClient = OkHttpClient()) {
    private val endpoint = (baseUrl.trimEnd('/') + "/user/balance").toHttpUrl().also {
        require(it.username.isEmpty() && it.password.isEmpty() && it.query == null && it.fragment == null) { "Invalid base URL" }
        require(it.isHttps || it.host in LOOPBACK) { "The balance endpoint requires HTTPS" }
    }
    private val http = client.newBuilder().followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).callTimeout(30, TimeUnit.SECONDS).build()

    suspend fun read(apiKey: String, now: () -> Long = System::currentTimeMillis): BalanceReading {
        require(apiKey.isNotEmpty() && apiKey.length <= 16_384 && apiKey.all { it.code in 33..126 }) { "Invalid API key" }
        val request = Request.Builder().url(endpoint).get().header("Accept", "application/json").apply {
            identity.headers().forEach { (name, value) -> header(name, value) }
            header("Authorization", "Bearer $apiKey")
        }.build()
        return try {
            val (status, body) = execute(request)
            if (status !in 200..299) BalanceReading(now(), error = ErrorExplainer.http(status, body))
            else try { BalanceParser.parse(body, now()) } catch (_: IllegalArgumentException) { BalanceReading(now(), error = UNREADABLE) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: IOException) { BalanceReading(now(), error = ErrorExplainer.explain(error)) }
    }

    private suspend fun execute(request: Request): Pair<Int, String> = suspendCancellableCoroutine { continuation ->
        val call = http.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val result = response.use {
                        val source = it.body?.source()
                        val body = when {
                            source == null -> ""
                            source.request(MAX_BODY + 1L) -> throw IOException("Balance response is too large")
                            else -> source.readUtf8()
                        }
                        it.code to body
                    }
                    if (continuation.isActive) continuation.resume(result)
                } catch (error: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
            }
        })
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://api.deepseek.com"
        const val UNREADABLE = "DeepSeek returned a balance this app could not read."
        private const val MAX_BODY = 64 * 1024
        private val LOOPBACK = setOf("localhost", "127.0.0.1", "::1")
    }
}

/** The balance line and "last read" line on each row of Settings > Accounts. */
object BalanceText {
    private val time = DateTimeFormatter.ofPattern("HH:mm")
    private val dateTime = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    fun summary(type: AccountType, reading: BalanceReading?): String = when {
        type == AccountType.DEEPSEEK_WEB -> "Balance: not available for web sign-in"
        reading == null -> "Balance: not read yet"
        reading.error != null -> "Balance: could not be read. ${reading.error}"
        reading.balances.isEmpty() -> "Balance: none reported"
        else -> "Balance: " + reading.balances.joinToString(", ") { "${it.total} ${it.currency}" } +
            if (reading.available == false) " (not enough for API calls)" else ""
    }

    fun readAt(reading: BalanceReading?, now: Long, zone: ZoneId): String? {
        if (reading == null) return null
        val read = Instant.ofEpochMilli(reading.readAt).atZone(zone)
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return "Last read " + if (read.toLocalDate() == today) "today " + time.format(read) else dateTime.format(read)
    }
}
