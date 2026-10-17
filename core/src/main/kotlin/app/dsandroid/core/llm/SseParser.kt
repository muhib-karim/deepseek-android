package app.dsandroid.core.llm

/** Incremental SSE framing. Call finish at EOF; the transport owns UTF-8 decoding. */
class SseParser(private val maxEventChars: Int = 2 * 1024 * 1024) {
    private val line = StringBuilder()
    private val data = mutableListOf<String>()
    private var eventChars = 0
    private var skipLf = false

    fun feed(chunk: String): List<String> {
        val events = mutableListOf<String>()
        for (char in chunk) {
            if (skipLf && char == '\n') { skipLf = false; continue }
            skipLf = false
            when (char) {
                '\r', '\n' -> {
                    consumeLine(events)
                    skipLf = char == '\r'
                }
                else -> {
                    line.append(char)
                    require(line.length + eventChars <= maxEventChars) { "SSE event too large" }
                }
            }
        }
        return events
    }

    fun finish(): List<String> {
        val events = mutableListOf<String>()
        if (line.isNotEmpty()) consumeLine(events)
        dispatch(events)
        return events
    }

    private fun consumeLine(events: MutableList<String>) {
        val value = line.toString()
        line.setLength(0)
        if (value.isEmpty()) dispatch(events)
        else if (value == "data" || value.startsWith("data:")) {
            val field = if (value == "data") "" else value.substring(5).removePrefix(" ")
            eventChars += field.length + 1
            require(eventChars <= maxEventChars) { "SSE event too large" }
            data.add(field)
        }
    }

    private fun dispatch(events: MutableList<String>) {
        if (data.isNotEmpty()) events.add(data.joinToString("\n"))
        data.clear()
        eventChars = 0
    }
}
