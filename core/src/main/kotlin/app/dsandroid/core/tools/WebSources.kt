package app.dsandroid.core.tools

import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

@Serializable
data class WebSource(val number: Int, val title: String, val url: String) {
    val domain: String get() = WebFetchUrlPolicy.normalizedHost(url).orEmpty()
}

/** Owned by one user turn, including retries. Conversation history never grants host trust. */
class TurnWebContext {
    private val entries = linkedMapOf<String, WebSource>()
    private val searchedHosts = linkedSetOf<String>()
    val sources: List<WebSource> get() = entries.values.toList()
    val searchHosts: Set<String> get() = searchedHosts.toSet()

    fun registerSearchResult(result: WebSearchResult): WebSource? {
        val source = registerFetchedPage(result.url, result.title) ?: return null
        searchedHosts.add(source.domain)
        return source
    }

    fun registerFetchedPage(url: String, title: String): WebSource? {
        val canonical = canonicalUrl(url) ?: return null
        entries[canonical]?.let { return it }
        val next = (entries.values.maxOfOrNull { it.number } ?: 0) + 1
        if (entries.size >= MAX_SOURCES || next > MAX_SOURCES) return null
        return WebSource(next, sourceTitle(title, canonical), canonical).also { entries[canonical] = it }
    }

    /** Restore only a checkpoint for this same turn; a new turn restores empty collections. */
    fun restore(sources: List<WebSource>, searchHosts: Set<String>) {
        entries.clear()
        searchedHosts.clear()
        val numbers = mutableSetOf<Int>()
        for (source in sources.take(MAX_SOURCES)) {
            val canonical = canonicalUrl(source.url) ?: continue
            if (source.number !in 1..MAX_SOURCES || canonical in entries || !numbers.add(source.number)) continue
            entries[canonical] = source.copy(title = sourceTitle(source.title, canonical), url = canonical)
        }
        val availableHosts = entries.values.map { it.domain }.toSet()
        searchHosts.take(MAX_SOURCES).mapNotNull(WebFetchUrlPolicy::normalizeHost).filterTo(searchedHosts) { it in availableHosts }
    }

    private fun canonicalUrl(value: String): String? {
        if (value.length > MAX_URL_LENGTH) return null
        val host = WebFetchUrlPolicy.normalizedHost(value) ?: return null
        return value.toHttpUrlOrNull()?.newBuilder()?.host(host)?.fragment(null)?.build()?.toString()
    }

    private fun sourceTitle(title: String, url: String) = webPlain(title).take(300).ifBlank {
        WebFetchUrlPolicy.normalizedHost(url).orEmpty()
    }

    private companion object {
        const val MAX_SOURCES = 64
        const val MAX_URL_LENGTH = 8192
    }
}
