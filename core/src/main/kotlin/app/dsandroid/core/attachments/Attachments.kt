package app.dsandroid.core.attachments

import app.dsandroid.core.llm.ChatMessage
import app.dsandroid.core.llm.ContentPart
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.util.Locale

enum class AttachmentKind(val label: String) {
    PDF("PDF"), DOCX("DOCX"), XLSX("XLSX"), PPTX("PPTX"), TEXT("text"), IMAGE("image"), UNKNOWN("unsupported");

    companion object {
        fun detect(mime: String?, name: String): AttachmentKind {
            val type = mime.orEmpty().substringBefore(';').lowercase(Locale.ROOT)
            val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
            return when {
                type == "application/pdf" -> PDF
                type == "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> DOCX
                type.startsWith("image/") -> IMAGE
                type.startsWith("text/") || type in setOf("application/json", "application/xml") -> TEXT
                extension == "pdf" -> PDF
                extension == "docx" -> DOCX
                extension == "xlsx" || type == "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" -> XLSX
                extension == "pptx" || type == "application/vnd.openxmlformats-officedocument.presentationml.presentation" -> PPTX
                extension in setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif") -> IMAGE
                extension in setOf("txt", "md", "markdown", "csv", "json", "xml", "log", "yaml", "yml") -> TEXT
                else -> UNKNOWN
            }
        }
    }
}

object PlainTextAttachment {
    const val MAX_CHARS = 200_000
    const val TRUNCATION_MARKER = "\n[Attachment truncated]"

    fun read(input: InputStream, maxChars: Int = MAX_CHARS): String {
        require(maxChars in 1..MAX_CHARS)
        // Enough for UTF-8/UTF-16, plus one character to detect truncation. Never read an unbounded file.
        val bytes = input.readNBytesCompat(maxChars * 4 + 4)
        return read(bytes, maxChars)
    }

    fun read(bytes: ByteArray, maxChars: Int = MAX_CHARS): String {
        require(maxChars in 1..MAX_CHARS)
        var overflowed = false
        val text = when {
            bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() -> String(bytes, 2, minOf(bytes.size - 2, maxChars * 2 + 4), Charsets.UTF_16LE)
            bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() -> String(bytes, 2, minOf(bytes.size - 2, maxChars * 2 + 4), Charsets.UTF_16BE)
            else -> {
                val start = if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) 3 else 0
                try {
                    val chars = CharBuffer.allocate(maxChars + 1)
                    val result = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes, start, bytes.size - start), chars, true)
                    if (result.isError) result.throwException()
                    overflowed = result.isOverflow
                    // Use Buffer's pre-Java-9 signature on Android API 26 as well.
                    (chars as java.nio.Buffer).flip()
                    chars.toString()
                } catch (_: java.nio.charset.CharacterCodingException) {
                    String(bytes, start, minOf(bytes.size - start, maxChars + 1), charset("windows-1252"))
                }
            }
        }
        return if (overflowed && text.length <= maxChars) text + TRUNCATION_MARKER else cap(text, maxChars)
    }

    fun cap(text: String, maxChars: Int = MAX_CHARS): String {
        require(maxChars in 1..MAX_CHARS)
        if (text.length <= maxChars) return text
        val end = if (Character.isHighSurrogate(text[maxChars - 1])) maxChars - 1 else maxChars
        return text.take(end) + TRUNCATION_MARKER
    }
}

/** Android-compatible bounded read (InputStream.readNBytes needs a newer Android API). */
internal fun InputStream.readNBytesCompat(limit: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream(minOf(limit, 8192))
    val buffer = ByteArray(8192)
    while (output.size() < limit) {
        val count = read(buffer, 0, minOf(buffer.size, limit - output.size()))
        if (count < 0) break
        if (count == 0) continue
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}

data class AttachmentContent(val name: String, val kind: AttachmentKind, val text: String = "", val imageDataUri: String? = null)

object AttachmentContextBuilder {
    const val MAX_FILES = 50
    fun message(prompt: String, attachments: List<AttachmentContent>, vision: Boolean): ChatMessage {
        require(attachments.size <= MAX_FILES) { "At most 50 files per chat" }
        val blocks = attachments.filter { it.kind != AttachmentKind.IMAGE && it.kind != AttachmentKind.UNKNOWN }.map {
            val name = it.name.replace(Regex("[\\r\\n\\p{Cntrl}]"), " ").take(200)
            val text = PlainTextAttachment.cap(it.text)
            "Attached file: $name (${it.kind.label}, ${text.length} chars)\n$text"
        }
        val text = (listOf(prompt.trim()).filter { it.isNotEmpty() } + blocks).joinToString("\n\n")
        val images = if (vision) attachments.filter { it.kind == AttachmentKind.IMAGE }.mapNotNull { it.imageDataUri } else emptyList()
        return if (images.isEmpty()) ChatMessage("user", text)
        else ChatMessage("user", parts = listOf(ContentPart.text(text)) + images.map(ContentPart::image))
    }
}
