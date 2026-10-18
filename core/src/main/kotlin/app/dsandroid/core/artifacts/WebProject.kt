package app.dsandroid.core.artifacts

import java.io.ByteArrayOutputStream
import java.net.URI
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** One immutable canvas version holds a bounded website or HTML slide deck and all its source files. */
@Serializable
data class WebProject(val kind: String = "website", val files: Map<String, String>) {
    fun validate() {
        require(kind in setOf("website", "slides"))
        require(files.size in 1..50 && "index.html" in files)
        var bytes = 0L
        files.forEach { (path, content) ->
            require(path.length <= 160 && path.split('/').all { it.matches(Regex("[A-Za-z0-9_-][A-Za-z0-9_.-]*")) })
            require(path.substringAfterLast('.') in types.keys)
            require(content.length <= 500_000)
            bytes += content.toByteArray(Charsets.UTF_8).size
            require(bytes <= 1_500_000)
        }
    }
    fun encode(): String { validate(); return json.encodeToString(this) }
    fun preview(): String { validate(); return rewrite(files.getValue("index.html"), "index.html") }
    fun resource(url: String): Pair<String, String>? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        if (uri.scheme != "https" || uri.rawAuthority != "artifact.invalid" || uri.rawPath.contains('%') || uri.rawQuery != null || uri.rawFragment != null) return null
        val path = uri.rawPath.removePrefix("/")
        val content = files[path] ?: return null
        return types.getValue(path.substringAfterLast('.')) to if (path.endsWith(".css")) rewrite(content, path) else content
    }
    fun zip(): ByteArray {
        validate()
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            files.forEach { (path, content) ->
                zip.putNextEntry(ZipEntry(path)); zip.write(content.toByteArray(Charsets.UTF_8)); zip.closeEntry()
            }
        }
        return output.toByteArray()
    }
    private fun rewrite(source: String, from: String): String {
        fun local(value: String): String {
            val uri = runCatching { URI(value) }.getOrNull() ?: return value
            if (uri.isAbsolute || uri.rawAuthority != null || value.startsWith('#') || uri.rawQuery != null) return value
            val resolved = URI("$ORIGIN/$from").resolve(uri).normalize()
            return if (resolved.rawAuthority == "artifact.invalid" && resolved.path.removePrefix("/") in files) resolved.toString() else value
        }
        val attributes = Regex("(?i)(\\b(?:src|href)\\s*=\\s*)([\"'])(.*?)\\2")
        val cssUrls = Regex("(?i)url\\(\\s*([\"']?)([^)\"']+)\\1\\s*\\)")
        return cssUrls.replace(attributes.replace(source) { m -> "${m.groupValues[1]}${m.groupValues[2]}${local(m.groupValues[3])}${m.groupValues[2]}" }) { m ->
            "url(\"${local(m.groupValues[2].trim())}\")"
        }
    }
    companion object {
        const val MIME = "application/vnd.dsandroid.web-project+json"
        const val ORIGIN = "https://artifact.invalid"
        private val json = Json { ignoreUnknownKeys = true }
        private val types = mapOf("html" to "text/html", "css" to "text/css", "js" to "application/javascript", "svg" to "image/svg+xml", "json" to "application/json")
        fun decode(source: String): WebProject {
            require(source.toByteArray(Charsets.UTF_8).size <= ArtifactStore.MAX_BYTES)
            return json.decodeFromString<WebProject>(source).also { it.validate() }
        }
    }
}
