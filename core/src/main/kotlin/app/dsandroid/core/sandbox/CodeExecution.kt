package app.dsandroid.core.sandbox

enum class CodeLanguage(val wireName: String) { JAVASCRIPT("javascript"), PYTHON("python") }

object CodeLimits {
    const val MAX_CODE_CHARS = 100_000
    const val MAX_OUTPUT_CHARS = 16_000
    const val WALL_TIME_MS = 15_000L
    const val MAX_RESULT_JSON_CHARS = 110_000
}

data class CodeRequest(val language: CodeLanguage, val code: String) {
    init { require(code.isNotBlank() && code.length <= CodeLimits.MAX_CODE_CHARS) { "Code must contain 1 to 100000 characters" } }
}

/** value is a printable representation; null means no return value, while "null" is a returned null. */
data class CodeExecution(
    val stdout: String = "",
    val stderr: String = "",
    val value: String? = null,
    val timedOut: Boolean = false,
    val truncated: Boolean = false,
    val failed: Boolean = false
) {
    fun bounded(): CodeExecution {
        var remaining = CodeLimits.MAX_OUTPUT_CHARS
        fun take(text: String): String = text.take(remaining).also { remaining -= it.length }
        val out = take(stdout)
        val err = take(stderr)
        val result = value?.let(::take)
        return copy(stdout = out, stderr = err, value = result,
            truncated = truncated || out.length != stdout.length || err.length != stderr.length || result?.length != value?.length)
    }
}

/** The platform adapter owns the wall-clock watchdog and must dispose execution on cancellation. */
fun interface CodeExecutor {
    suspend fun execute(request: CodeRequest): CodeExecution
}
