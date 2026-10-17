package app.dsandroid.core.agent

import app.dsandroid.core.llm.ProviderHttpException
import java.io.IOException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException
import kotlin.random.Random

class RetryPolicy(
    val budgetMs: Long = 300_000,
    private val jitter: () -> Double = { Random.nextDouble(0.8, 1.2) },
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    init { require(budgetMs >= 0) }
    fun isRetryable(error: Throwable): Boolean = when (error) {
        is CancellationException -> false
        is ProviderHttpException -> error.status == 429 || error.status in 500..599
        is IOException -> true
        else -> false
    }
    fun delayMs(error: Throwable, attempt: Int, elapsedMs: Long): Long? {
        if (!isRetryable(error) || elapsedMs >= budgetMs) return null
        val backoff = (1000L * (1L shl attempt.coerceIn(0, 5))).coerceAtMost(30_000)
        val retryAfter = (error as? ProviderHttpException)?.retryAfter?.let { raw ->
            raw.trim().toLongOrNull()?.takeIf { it >= 0 }?.let { if (it > Long.MAX_VALUE / 1000) Long.MAX_VALUE else it * 1000 }
                ?: runCatching { (ZonedDateTime.parse(raw, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() - nowMs()).coerceAtLeast(0) }.getOrNull()
        }
        val delay = maxOf((backoff * jitter().coerceIn(0.0, 2.0)).toLong().coerceIn(1, 30_000), retryAfter ?: 0)
        return delay.takeIf { it <= budgetMs - elapsedMs }
    }
}
