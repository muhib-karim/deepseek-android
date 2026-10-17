package app.dsandroid.core.llm

/** Descriptive metadata, never another application's credentials or identity. */
data class ClientIdentity(val version: String, val language: String, val utcOffsetSeconds: Int) {
    fun headers(): Map<String, String> = mapOf(
        "User-Agent" to "DeepSeek-Android-Unofficial/$version (Android)",
        "x-dsh-client-name" to "DeepSeek-Android-Unofficial",
        "x-dsh-client-version" to version,
        "x-dsh-client-platform" to "android",
        "x-dsh-client-language" to language,
        "x-dsh-client-timezone-offset" to utcOffsetSeconds.toString(),
    )
}
