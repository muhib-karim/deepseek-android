package app.dsandroid.tasks

import android.content.Context
import androidx.work.*
import app.dsandroid.core.tasks.*
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

class TaskScheduler(context: Context) {
    private val app = context.applicationContext
    val store = TaskStore(File(app.filesDir, "tasks"))
    suspend fun schedule(task: ScheduledTask) = withContext(Dispatchers.IO + NonCancellable) {
        val now = System.currentTimeMillis()
        task.validate(now)
        store.save(task, now)
        try { enqueue(task) }
        catch (error: Exception) { store.cancel(task.id); throw error }
    }
    // Repair a process exit between writing our journal and enqueueing WorkManager.
    suspend fun reconcile() = withContext(Dispatchers.IO) {
        val work = WorkManager.getInstance(app)
        store.tasks().filter { it.active }.forEach { task ->
            if (work.getWorkInfosForUniqueWork("scheduled-${task.id}").get().none { !it.state.isFinished }) enqueue(task)
        }
    }
    private fun enqueue(task: ScheduledTask) {
        val data = workDataOf("task_id" to task.id)
        val delay = (task.runAt - System.currentTimeMillis()).coerceAtLeast(0)
        val work = WorkManager.getInstance(app)
        // No network constraints: ACCESS_NETWORK_STATE is deliberately absent.
        if (task.intervalMinutes == 0L) {
            val request = OneTimeWorkRequestBuilder<ScheduledPromptWorker>().setInputData(data).setInitialDelay(delay, TimeUnit.MILLISECONDS).build()
            work.enqueueUniqueWork("scheduled-${task.id}", ExistingWorkPolicy.KEEP, request).result.get()
        } else {
            val request = PeriodicWorkRequestBuilder<ScheduledPromptWorker>(task.intervalMinutes, TimeUnit.MINUTES)
                .setInputData(data).setInitialDelay(delay, TimeUnit.MILLISECONDS).build()
            work.enqueueUniquePeriodicWork("scheduled-${task.id}", ExistingPeriodicWorkPolicy.KEEP, request).result.get()
        }
    }
    suspend fun cancel(id: String) = withContext(Dispatchers.IO + NonCancellable) {
        store.cancel(id)
        WorkManager.getInstance(app).cancelUniqueWork("scheduled-$id").result.get()
        Unit
    }
}
