package app.dsandroid.ui

/** Main-thread generation gate for asynchronous speech-service callbacks. */
internal class DictationSession {
    private var generation = 0L
    private var active = false
    private var held = false
    fun begin(): Long { generation++; active = true; held = true; return generation }
    fun accepts(token: Long): Boolean = active && token == generation
    fun canRecord(token: Long): Boolean = accepts(token) && held
    fun release(token: Long) { if(accepts(token)) held = false }
    fun cancel() { generation++; active = false; held = false }
}
