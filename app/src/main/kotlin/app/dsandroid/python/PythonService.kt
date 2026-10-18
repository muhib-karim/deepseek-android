package app.dsandroid.python

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import android.os.Process
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform

internal object PythonProtocol {
    const val RUN = 1
    const val STARTED = 2
    const val RESULT = 3
}

/**
 * Hosts the Chaquopy interpreter in the separate ":python" process so a runaway or crashing script can be
 * killed without touching the app. Runs one request at a time on a worker thread; replies STARTED (with the
 * pid, once the interpreter is ready) and then RESULT. Not exported.
 */
class PythonService : Service() {
    private lateinit var worker: HandlerThread
    private lateinit var messenger: Messenger

    override fun onCreate() {
        super.onCreate()
        worker = HandlerThread("python-runner").apply { start() }
        messenger = Messenger(Handler(worker.looper) { message -> handle(message); true })
    }

    override fun onBind(intent: Intent): IBinder = messenger.binder

    override fun onDestroy() {
        worker.quitSafely()
        super.onDestroy()
    }

    private fun handle(message: Message) {
        if (message.what != PythonProtocol.RUN) return
        val reply = message.replyTo ?: return
        val id = message.arg1
        val request = Bundle(message.data)
        val result = Bundle()
        try {
            if (!Python.isStarted()) Python.start(AndroidPlatform(applicationContext))
            reply.send(Message.obtain(null, PythonProtocol.STARTED, id, Process.myPid()))
            val output = Python.getInstance().getModule("ds_runner").callAttr("run",
                request.getString("code"), request.getString("cwd"), request.getString("site"),
                request.getString("namespace"), request.getString("mpl"))
            result.putString("result", output.toString())
        } catch (error: Throwable) {
            result.putString("failure", "${error.javaClass.simpleName}: ${error.message.orEmpty()}".take(2000))
        }
        runCatching { reply.send(Message.obtain(null, PythonProtocol.RESULT, id, 0).apply { data = result }) }
    }
}
