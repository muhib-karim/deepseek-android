package app.dsandroid.attachments

import app.dsandroid.core.attachments.PlainTextAttachment
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.InputStream
import java.io.Writer

object PdfTextExtractor {
    private class LimitReached : java.io.IOException()
    fun extract(input: InputStream): String = PDDocument.load(input).use { document ->
        val text = StringBuilder()
        var truncated = false
        val writer = object : Writer() {
            override fun write(chars: CharArray, offset: Int, count: Int) {
                val remaining = PlainTextAttachment.MAX_CHARS - text.length
                text.append(chars, offset, minOf(count, remaining))
                if (count > remaining) throw LimitReached()
            }
            override fun flush() = Unit
            override fun close() = Unit
        }
        try { PDFTextStripper().writeText(document, writer) }
        catch (_: LimitReached) { truncated = true }
        text.toString() + if (truncated) PlainTextAttachment.TRUNCATION_MARKER else ""
    }
}
