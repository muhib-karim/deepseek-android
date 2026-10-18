package app.dsandroid.core.attachments

import java.net.URI

object SharedContentPolicy {
    const val MAX_TEXT = 30_000
    fun uris(values: List<String>, privateAuthority: String): List<String> = values.asSequence()
        .filter { value ->
            value.length <= 4096 && runCatching {
                val uri = URI(value)
                uri.scheme == "content" && !uri.rawAuthority.isNullOrBlank() &&
                    uri.rawAuthority == uri.authority && uri.authority != privateAuthority && uri.userInfo == null
            }.getOrDefault(false)
        }.distinct().take(AttachmentContextBuilder.MAX_FILES).toList()
}
