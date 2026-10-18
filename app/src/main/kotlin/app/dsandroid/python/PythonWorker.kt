package app.dsandroid.python

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.os.RemoteException
import app.dsandroid.core.python.PythonExecutor
import app.dsandroid.core.python.PythonOutcome
import app.dsandroid.core.python.PythonRun
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*

/**
 * [PythonExecutor] backed by [PythonService]. The user timeout starts once the interpreter is ready; on
 * timeout, Stop or a dead binder the worker process is killed so no code keeps running in the background.
 */
class PythonWorker(context: Context, private val siteDir: File) : PythonExecutor {
    companion object {
        private const val CONNECT_TIMEOUT_MS = 20_000L
        private const val STARTUP_TIMEOUT_MS = 60_000L
    }

    private class Connection(val service: ServiceConnection, val messenger: Messenger)

    private val app = context.applicationContext
    private val mutex = Mutex()
    private var connection: Connection? = null
    private var nextId = 0

    override suspend fun run(request: PythonRun): PythonOutcome = mutex.withLock {
        val current = connect()
        val id = ++nextId
        val started = CompletableDeferred<Int>()
        val finished = CompletableDeferred<Bundle>()
        val replies = Messenger(Handler(Looper.getMainLooper()) { message ->
            if (message.arg1 == id) when (message.what) {
                PythonProtocol.STARTED -> started.complete(message.arg2)
                PythonProtocol.RESULT -> finished.complete(Bundle(message.data))
            }
            true
        })
        val death = IBinder.DeathRecipient {
            finished.complete(Bundle().apply { putString("failure", "The Python worker stopped unexpectedly.") })
            started.complete(-1)
        }
        var pid: Int? = null
        try {
            current.messenger.binder.linkToDeath(death, 0)
            current.messenger.send(Message.obtain(null, PythonProtocol.RUN, id, 0).apply {
                data = Bundle().apply {
                    putString("code", request.code)
                    putString("cwd", request.workingDir.absolutePath)
                    putString("site", siteDir.absolutePath)
                    putString("namespace", request.namespace)
                    putString("mpl", File(app.cacheDir, "matplotlib").absolutePath)
                }
                replyTo = replies
            })
            pid = withTimeoutOrNull(STARTUP_TIMEOUT_MS) { started.await() }
            if (pid == null) {
                stop(kill = true, pid = null)
                return@withLock PythonOutcome(error = "Python did not start within ${STARTUP_TIMEOUT_MS / 1000} seconds.", timedOut = true)
            }
            val bundle = withTimeoutOrNull(request.timeoutSeconds * 1000L) { finished.await() }
            if (bundle == null) {
                stop(kill = true, pid = pid)
                return@withLock PythonOutcome(error = "Stopped after ${request.timeoutSeconds} seconds. Python restarted, so this chat's variables were cleared.", timedOut = true)
            }
            if (bundle.containsKey("failure") && !current.messenger.binder.isBinderAlive) stop(kill = false, pid = null)
            parse(bundle)
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) { stop(kill = true, pid = pid) }
            throw cancelled
        } catch (_: RemoteException) {
            stop(kill = true, pid = pid)
            PythonOutcome(error = "The Python worker stopped. Try again.")
        } finally {
            runCatching { current.messenger.binder.unlinkToDeath(death, 0) }
        }
    }

    private suspend fun connect(): Connection {
        connection?.let { existing ->
            if (existing.messenger.binder.isBinderAlive) return existing
            stop(kill = false, pid = null)
        }
        val bound = CompletableDeferred<Messenger>()
        val service = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) { if (binder != null) bound.complete(Messenger(binder)) }
            override fun onServiceDisconnected(name: ComponentName?) = Unit
            override fun onBindingDied(name: ComponentName?) { bound.completeExceptionally(IllegalStateException("Python worker binding died")) }
        }
        val accepted = withContext(Dispatchers.Main) { app.bindService(Intent(app, PythonService::class.java), service, Context.BIND_AUTO_CREATE) }
        check(accepted) { "The Python worker could not be started" }
        val messenger = withTimeoutOrNull(CONNECT_TIMEOUT_MS) { bound.await() }
        if (messenger == null) {
            runCatching { app.unbindService(service) }
            throw IllegalStateException("The Python worker did not respond")
        }
        return Connection(service, messenger).also { connection = it }
    }

    /** Unbinds and, when [kill] is set, terminates the worker process (only this app's own ":python" process). */
    private fun stop(kill: Boolean, pid: Int?) {
        val current = connection
        connection = null
        if (current != null) runCatching { app.unbindService(current.service) }
        if (!kill) return
        val target = pid?.takeIf { it > 0 } ?: workerPid()
        if (target != null && target != Process.myPid()) Process.killProcess(target)
    }

    private fun workerPid(): Int? = (app.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)
        ?.runningAppProcesses?.firstOrNull { it.processName == "${app.packageName}:python" }?.pid

    private fun parse(bundle: Bundle): PythonOutcome {
        bundle.getString("failure")?.let { return PythonOutcome(error = it) }
        val json = runCatching { Json.parseToJsonElement(bundle.getString("result").orEmpty()).jsonObject }.getOrNull()
            ?: return PythonOutcome(error = "Python returned an unreadable result.")
        fun text(key: String) = (json[key] as? JsonPrimitive)?.contentOrNull
        return PythonOutcome(
            stdout = text("stdout").orEmpty(),
            stderr = text("stderr").orEmpty(),
            error = text("error"),
            truncated = (json["truncated"] as? JsonPrimitive)?.booleanOrNull == true,
        )
    }
}
