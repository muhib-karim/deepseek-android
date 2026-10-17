package app.dsandroid.core.attachments

import java.io.InputStream
import java.io.StringReader
import java.util.zip.ZipInputStream
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.InputSource
import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler

object DocxTextExtractor {
    private const val WORD = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    private const val MAX_XML_BYTES = 4 * 1024 * 1024

    fun extract(input: InputStream, maxChars: Int = PlainTextAttachment.MAX_CHARS): String {
        require(maxChars in 1..PlainTextAttachment.MAX_CHARS)
        var document: ByteArray? = null
        var total = 0
        var entries = 0
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                require(++entries <= 256) { "Too many DOCX entries" }
                val bytes = zip.readNBytesCompat(MAX_XML_BYTES + 1)
                total += bytes.size
                require(bytes.size <= MAX_XML_BYTES && total <= 16 * 1024 * 1024) { "DOCX is too large" }
                if (entry.name == "word/document.xml") { document = bytes; break }
            }
        }
        val bytes = requireNotNull(document) { "DOCX has no document text" }
        val xml = when {
            bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() -> String(bytes, Charsets.UTF_16)
            bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() -> String(bytes, Charsets.UTF_16)
            else -> String(bytes, Charsets.UTF_8).removePrefix("\uFEFF")
        }
        require(!xml.contains("<!DOCTYPE", ignoreCase = true) && !xml.contains("<!ENTITY", ignoreCase = true)) { "DOCX entity declarations are not allowed" }
        val result = StringBuilder()
        var paragraphs = 0
        var inText = false
        val handler = object : DefaultHandler() {
            override fun resolveEntity(publicId: String?, systemId: String?): InputSource = InputSource(StringReader(""))
            override fun startElement(uri: String, localName: String, qName: String, attributes: Attributes) {
                if (uri == WORD && localName == "p") paragraphs++
                if (uri == WORD && localName == "t" && paragraphs > 0) inText = true
            }
            override fun characters(ch: CharArray, start: Int, length: Int) {
                // XML is already capped at 4 MB, so paragraph separators can be trimmed before the character cap.
                if (inText) result.append(ch, start, length)
            }
            override fun endElement(uri: String, localName: String, qName: String) {
                if (uri == WORD && localName == "t") inText = false
                if (uri == WORD && localName == "p") {
                    paragraphs--
                    result.append('\n')
                }
            }
        }
        val reader = SAXParserFactory.newInstance().apply { isNamespaceAware = true }.newSAXParser().xmlReader
        reader.contentHandler = handler
        reader.entityResolver = handler
        reader.errorHandler = handler
        reader.parse(InputSource(StringReader(xml)))
        return PlainTextAttachment.cap(result.toString().trimEnd('\n'), maxChars)
    }
}
