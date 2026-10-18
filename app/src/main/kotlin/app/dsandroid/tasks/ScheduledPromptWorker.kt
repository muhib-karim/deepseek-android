package app.dsandroid.tasks

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.dsandroid.BuildConfig
import app.dsandroid.R
import app.dsandroid.core.llm.*
import app.dsandroid.core.tasks.*
import app.dsandroid.data.*
import java.io.File
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.*
import okhttp3.HttpUrl.Companion.toHttpUrl

/** Background tasks never advertise tools and cannot approve actions or pick another account. */
class ScheduledPromptWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val store = TaskStore(File(applicationContext.filesDir, "tasks"))
        val task = store.tasks().find { it.id == inputData.getString("task_id") && it.active } ?: return@withContext Result.success()
        val now = System.currentTimeMillis()
        val cycle = if (task.intervalMinutes == 0L) 0L else ((now - task.runAt).coerceAtLeast(0) / (task.intervalMinutes * 60_000))
        val run = "${task.id}-$cycle"
        if (!store.claim(task.id, run)) {
            if (store.results().none { it.id == run }) store.complete(TaskResult(run, task.id, task.title, applicationContext.getString(R.string.task_interrupted), now, error = true))
            return@withContext Result.success()
        }
        try {
            val config = SettingsStore(applicationContext).load()
            if (config.runIdentity != task.providerKey) {
                store.complete(TaskResult(run, task.id, task.title, applicationContext.getString(R.string.task_account_changed), now, error = true))
                return@withContext Result.success()
            }
            val endpoint = config.baseUrl.toHttpUrl()
            require(endpoint.isHttps && endpoint.username.isEmpty() && endpoint.password.isEmpty() && endpoint.query == null && endpoint.fragment == null)
            val auth = when (config.provider) {
                Provider.API_KEY -> ApiKeyAuth(config.apiKey)
                Provider.CUSTOM -> CustomEndpointAuth(config.customKey)
                Provider.ACCOUNT -> {
                    require(config.accountOrigin == "${endpoint.scheme}://${endpoint.host}:${endpoint.port}")
                    DeepSeekAccountAuth(config.accountToken)
                }
            }
            val identity = ClientIdentity(BuildConfig.VERSION_NAME, Locale.getDefault().toLanguageTag(), TimeZone.getDefault().getOffset(now) / 1000)
            val answer = ScheduledPromptRunner.answer(task, config.runIdentity) { OpenAiCompatibleClient(config.baseUrl, task.model, auth, identity, client = taskHttp) }
            store.complete(TaskResult(run, task.id, task.title, answer, System.currentTimeMillis()))
            Result.success()
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) { store.complete(TaskResult(run, task.id, task.title, applicationContext.getString(R.string.task_interrupted), System.currentTimeMillis(), error = true)) }
            if (cancelled is TimeoutCancellationException) Result.success() else throw cancelled
        } catch (_: Exception) {
            runCatching {
                app.dsandroid.core.diagnostics.DiagnosticStore(File(applicationContext.filesDir, "diagnostics"))
                    .append(app.dsandroid.core.diagnostics.DiagnosticKind.ERROR, "Scheduled prompt failed")
            }
            store.complete(TaskResult(run, task.id, task.title, applicationContext.getString(R.string.task_run_failed), System.currentTimeMillis(), error = true))
            Result.success() // No hidden retries, account fallback or duplicate billable inference.
        }
    }
    companion object {
        private val taskHttp = okhttp3.OkHttpClient.Builder().retryOnConnectionFailure(false).build()
    }
}
