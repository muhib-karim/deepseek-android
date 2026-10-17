package app.dsandroid.core.sandbox

/** Only application-controlled runtime bytes may be fetched; code never controls these URLs. */
object PythonRuntime {
    const val BASE_URL = "https://cdn.jsdelivr.net/pyodide/v0.26.4/full/"
    // Reserved .invalid origin is served exclusively from private cache by the Android adapter.
    const val CACHE_BASE_URL = "https://sandbox.invalid/python/"
    const val MAX_BYTES = 64 * 1024 * 1024
    val files: Set<String> = setOf("pyodide.js", "pyodide.asm.js", "pyodide.asm.wasm", "python_stdlib.zip", "pyodide-lock.json")
    fun isAllowedUrl(url: String): Boolean = files.any { url == BASE_URL + it }
    fun cachedFile(url: String): String? = files.firstOrNull { url == CACHE_BASE_URL + it }
}
