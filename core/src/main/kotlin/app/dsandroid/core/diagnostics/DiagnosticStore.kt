package app.dsandroid.core.diagnostics

import app.dsandroid.core.backup.SecretText
import app.dsandroid.core.persistence.JsonFileStore
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable enum class DiagnosticKind { ERROR, TOOL_FAILURE }
@Serializable data class DiagnosticEntry(val timestamp: Long, val kind: DiagnosticKind, val message: String)

/** Shared lock serializes UI and worker instances. Only redacted, bounded strings reach disk. */
class DiagnosticStore(directory: File, private val now: () -> Long = System::currentTimeMillis) {
    private val files = JsonFileStore(directory, 4 * 1024 * 1024)
    fun entries(): List<DiagnosticEntry> = synchronized(lock) {
        files.read("recent")?.let { Json.decodeFromString<List<DiagnosticEntry>>(it) }.orEmpty().takeLast(200)
    }
    fun append(kind: DiagnosticKind, message: String, credentials: Set<String> = emptySet()) = synchronized(lock) {
        val safe = if (message.length > 100_000) "Oversized diagnostic omitted" else SecretText.redact(message, credentials).replace(Regex("[A-Za-z0-9_+/=-]{32,}"), "[redacted]").take(2000)
        // A corrupt previous log must not prevent recording future errors.
        val previous = runCatching { entries() }.getOrDefault(emptyList())
        files.write("recent", Json.encodeToString((previous + DiagnosticEntry(now(), kind, safe)).takeLast(200)))
    }
    fun clear() = synchronized(lock) { files.delete("recent") }
    companion object { private val lock = Any() }
}
