package app.dsandroid.core.backup

/** Conservative local scrubbing; callers must never pass credentials as diagnostic context. */
object SecretText {
    private val patterns = listOf(
        Regex("(?i)\\bbearer\\s+[A-Za-z0-9._~+/=-]+"),
        Regex("(?i)\\b(?:authorization|proxy-authorization|cookie|set-cookie)[\\\"']?\\s*[:=]\\s*[^\\r\\n]+"),
        Regex("(?i)[\\\"]?\\b(?:api[-_]?key|access[-_]?token|refresh[-_]?token|account[-_]?token|auth[-_]?token|connector[-_]?token|password|(?:client[-_]?)?secret|credentials?|session[-_]?id|token)[\\\"]?\\s*[:=]\\s*(?:[\\\"][^\\\"]*[\\\"]|'[^']*'|[^\\s,;&}]+)"),
        Regex("\\bsk-[A-Za-z0-9_-]+"),
        Regex("\\beyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+"),
        Regex("[A-Za-z0-9.!#%&'*+/=?^_`{|}~-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}"),
        Regex("-----BEGIN [^-]*PRIVATE KEY-----[\\s\\S]*?(?:-----END [^-]*PRIVATE KEY-----|$)"),
    )
    fun redact(text: String, credentials: Set<String> = emptySet()): String {
        var result = text
        credentials.filter { it.isNotEmpty() }.sortedByDescending { it.length }.forEach { result = result.replace(it, "[redacted]") }
        patterns.forEach { result = it.replace(result, "[redacted]") }
        return result
    }
}
