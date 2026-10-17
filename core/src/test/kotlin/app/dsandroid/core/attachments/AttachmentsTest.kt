package app.dsandroid.core.attachments

import app.dsandroid.core.llm.*
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class AttachmentsTest {
    @Test fun detectsMimeAndCaseInsensitiveExtensions() {
        assertEquals(AttachmentKind.PDF, AttachmentKind.detect("application/pdf", "download"))
        assertEquals(AttachmentKind.DOCX, AttachmentKind.detect("application/octet-stream", "REPORT.DOCX"))
        assertEquals(AttachmentKind.IMAGE, AttachmentKind.detect("image/webp", "photo"))
        assertEquals(AttachmentKind.TEXT, AttachmentKind.detect(null, "notes.JSON"))
        assertEquals(AttachmentKind.UNKNOWN, AttachmentKind.detect(null, "archive.zip"))
    }

    private fun docx(xml: String): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            zip.putNextEntry(ZipEntry("ignored.xml")); zip.write("ignored".toByteArray()); zip.closeEntry()
            zip.putNextEntry(ZipEntry("word/document.xml")); zip.write(xml.toByteArray()); zip.closeEntry()
        }
        return bytes.toByteArray()
    }

    @Test fun docxReadsOnlyParagraphTextAndDecodesEntities() {
        val xml = """<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>
          <w:p><w:r><w:t>Hello &amp; </w:t><w:instrText>IGNORE</w:instrText><w:t>world</w:t></w:r></w:p>
          <w:p><w:r><w:t>Second</w:t></w:r></w:p><w:t>outside paragraph</w:t>
        </w:body></w:document>"""
        assertEquals("Hello & world\nSecond", DocxTextExtractor.extract(docx(xml).inputStream()))
    }

    @Test fun docxRejectsDoctypeAndMissingDocument() {
        try { DocxTextExtractor.extract(docx("<!DOCTYPE x [<!ENTITY a 'expanded'>]><x>&a;</x>").inputStream()); fail() }
        catch (_: IllegalArgumentException) { }
        val bytes = ByteArrayOutputStream().also { ZipOutputStream(it).close() }.toByteArray()
        try { DocxTextExtractor.extract(bytes.inputStream()); fail() } catch (_: IllegalArgumentException) { }
    }
    @Test fun docxMarksTruncationAcrossParagraphBoundary() {
        val xml = """<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:p><w:t>abc</w:t></w:p><w:p><w:t>def</w:t></w:p></w:document>"""
        assertEquals("abc" + PlainTextAttachment.TRUNCATION_MARKER, DocxTextExtractor.extract(docx(xml).inputStream(), 3))
    }

    @Test fun textSupportsBomFallbackAndVisibleTruncation() {
        assertEquals("hello", PlainTextAttachment.read(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "hello".toByteArray()))
        assertEquals("snow", PlainTextAttachment.read("snow".toByteArray(Charsets.UTF_16)))
        assertEquals("café", PlainTextAttachment.read(byteArrayOf(99, 97, 102, 0xE9.toByte())))
        assertEquals("abc" + PlainTextAttachment.TRUNCATION_MARKER, PlainTextAttachment.read("abcdef".byteInputStream(), 3))
        assertEquals("abc", PlainTextAttachment.read("abc".byteInputStream(), 3))
    }
    @Test fun boundedUtf8ReadDoesNotCorruptEarlierCharactersAtPartialTail() {
        val text = "é" + "a".repeat(13) + "😀" + "tail"
        assertEquals("éaa" + PlainTextAttachment.TRUNCATION_MARKER, PlainTextAttachment.read(text.byteInputStream(), 3))
        assertEquals("abc" + PlainTextAttachment.TRUNCATION_MARKER, PlainTextAttachment.read("abc😀".byteInputStream(), 3))
    }

    @Test fun contextAndVisionUseOpenAiContentInsteadOfLeakingImagesToTextModels() {
        val files = listOf(AttachmentContent("notes.txt", AttachmentKind.TEXT, "hello"),
            AttachmentContent("photo.jpg", AttachmentKind.IMAGE, imageDataUri = "data:image/jpeg;base64,AA=="))
        val plain = AttachmentContextBuilder.message("Summarize", files, false)
        assertEquals("Summarize\n\nAttached file: notes.txt (text, 5 chars)\nhello", plain.content)
        val plainJson = LlmJson.parseToJsonElement(LlmJson.encodeToString(plain)).jsonObject
        assertTrue(plainJson.getValue("content") is JsonPrimitive)
        assertFalse(plainJson.toString().contains("base64"))
        val vision = AttachmentContextBuilder.message("Summarize", files, true)
        val encoded = LlmJson.encodeToString(vision)
        val parts = LlmJson.parseToJsonElement(encoded).jsonObject.getValue("content").jsonArray
        assertEquals("text", parts[0].jsonObject.getValue("type").jsonPrimitive.content)
        assertEquals("data:image/jpeg;base64,AA==", parts[1].jsonObject.getValue("image_url").jsonObject.getValue("url").jsonPrimitive.content)
        assertEquals(vision, LlmJson.decodeFromString<ChatMessage>(encoded))
    }
}
