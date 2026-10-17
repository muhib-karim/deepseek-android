package app.dsandroid.tools

import android.graphics.Color
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import app.dsandroid.core.tools.*
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.JsonObject
import kotlin.coroutines.coroutineContext

/** Text-only PDF output; no HTML execution or external resources. A4 is 595 x 842 points. */
class CreatePdfTool : Tool {
    override val name = "create_pdf"
    override val description = "Create a new A4 PDF from plain text. Markdown punctuation is kept as text."
    override val risk = Risk.SAFE
    override val kind = ToolKind.WRITE
    override val schema = objectSchema(mapOf("fileName" to stringSchema("A new file ending in .pdf"), "content" to stringSchema("Text to lay out across pages")))

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        val content = args.stringArg("content")
        require(content.length <= 100_000) { "PDF text is too long" }
        val paint = TextPaint().apply { color = Color.BLACK; textSize = 12f; isAntiAlias = true }
        val layout = StaticLayout.Builder.obtain(content, 0, content.length, paint, 499)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(false).setLineSpacing(2f, 1f).build()
        val document = PdfDocument()
        val output = ByteArrayOutputStream()
        try {
            var firstLine = 0
            var pageNumber = 1
            do {
                coroutineContext.ensureActive()
                val top = layout.getLineTop(firstLine)
                var endLine = firstLine + 1
                while (endLine < layout.lineCount && layout.getLineBottom(endLine) - top <= 746) endLine++
                val bottom = layout.getLineBottom(endLine - 1)
                val page = document.startPage(PdfDocument.PageInfo.Builder(595, 842, pageNumber++).create())
                try {
                    page.canvas.save()
                    page.canvas.translate(48f, 48f)
                    page.canvas.clipRect(0, 0, 499, minOf(746, bottom - top))
                    page.canvas.translate(0f, -top.toFloat())
                    layout.draw(page.canvas)
                    page.canvas.restore()
                } finally { document.finishPage(page) }
                firstLine = endLine
            } while (firstLine < layout.lineCount)
            document.writeTo(output)
        } finally { document.close() }
        return writeArtifact(ctx, args.stringArg("fileName"), ".pdf", "application/pdf", output.toByteArray())
    }
}
