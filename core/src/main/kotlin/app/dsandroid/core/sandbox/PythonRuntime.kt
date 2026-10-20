package app.dsandroid.core.sandbox

/** Only application-controlled runtime bytes may be fetched; code never controls these URLs. */
object PythonRuntime {
    const val VERSION = "0.26.4"
    const val BASE_URL = "https://cdn.jsdelivr.net/pyodide/v0.26.4/full/"
    // Reserved .invalid origin is served exclusively from private cache by the Android adapter.
    const val CACHE_BASE_URL = "https://sandbox.invalid/python/"
    const val MAX_BYTES = 64 * 1024 * 1024

    /**
     * SHA-256 of each runtime file. Checked on 2026-10-20: the files at [BASE_URL] are byte-identical to those in the npm
     * package pyodide@0.26.4, whose tarball matches the registry's published sha512 integrity. A download or cached file
     * with any other digest is never used.
     */
    val SHA256: Map<String, String> = linkedMapOf(
        "pyodide.js" to "c0069107621d5b942a659e737a12e774cc0451feaa2256f475d72e071d844ec7",
        "pyodide.asm.js" to "919560652ed3dad3707cb3a394785da1e046fb13dc0defa162058ff230cb7eed",
        "pyodide.asm.wasm" to "b7e66a19427a55010ac3367c1b6c64b893f9826f783412945fdf0c3337f3bc94",
        "python_stdlib.zip" to "72894522b791858b9d613ac786b951d8b5094035dcf376313ea24a466810f336",
        "pyodide-lock.json" to "cd50b49de944c579045e122fe8628b31f9ce446379f032f36c05e273d38766e0",
    )
    val files: Set<String> = SHA256.keys
    fun isAllowedUrl(url: String): Boolean = files.any { url == BASE_URL + it }
    fun cachedFile(url: String): String? = files.firstOrNull { url == CACHE_BASE_URL + it }
    /** True when [sha256Hex] (any case) is the pinned digest of runtime file [name]. */
    fun matchesPin(name: String, sha256Hex: String): Boolean = SHA256[name]?.equals(sha256Hex, ignoreCase = true) == true
}
