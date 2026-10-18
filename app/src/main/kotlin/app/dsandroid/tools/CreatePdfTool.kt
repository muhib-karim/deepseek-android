package app.dsandroid.tools

import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import app.dsandroid.core.tools.*
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.JsonObject
import kotlin.coroutines.coroutineContext

/** Bounded A4 text/table rendering shared by the tool and conversation export. */
class CreatePdfTool : Tool {
    override val name = "create_pdf"
    override val description = "Create a PDF with title, heading/paragraph/table blocks, or plain text content."
    override val risk = Risk.SAFE
    override val kind = ToolKind.WRITE
    override val schema = PdfContent.schema

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        val rows = PdfContent.rows(args)
        val document = PdfDocument()
        val output = ByteArrayOutputStream()
        var page: PdfDocument.Page? = null
        var pageNumber = 0
        var y = 48f
        val border = Paint().apply { color = Color.GRAY; style = Paint.Style.STROKE; strokeWidth = 0.5f }
        fun nextPage() {
            page?.let(document::finishPage)
            page = null
            require(++pageNumber <= 200) { "PDF exceeds 200 pages" }
            page = document.startPage(PdfDocument.PageInfo.Builder(595, 842, pageNumber).create())
            y = 48f
        }
        try {
            nextPage()
            for (row in rows) {
                coroutineContext.ensureActive()
                val cellWidth = 499f / row.cells.size
                val inset = if (row.table) 5f else 0f
                val paint = TextPaint().apply {
                    color = Color.BLACK; textSize = if (row.heading) 18f else 12f; isAntiAlias = true
                    typeface = if (row.heading) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                }
                val layouts = row.cells.map { text ->
                    StaticLayout.Builder.obtain(text, 0, text.length, paint, (cellWidth - inset * 2).toInt())
                        .setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(false).setLineSpacing(2f, 1f).build()
                }
                // Advance by whole lines in every cell, including rows longer than a page.
                val firstLines = IntArray(layouts.size)
                do {
                    coroutineContext.ensureActive()
                    if (794f - y < paint.fontSpacing + inset * 2 + 4f) nextPage()
                    val available = (794f - y - inset * 2).toInt()
                    val ends = layouts.mapIndexed { index, layout ->
                        val start = firstLines[index]
                        if (start >= layout.lineCount) start else {
                            var end = start + 1
                            while (end < layout.lineCount && layout.getLineBottom(end) - layout.getLineTop(start) <= available) end++
                            end
                        }
                    }
                    val height = layouts.indices.maxOf { i ->
                        if (firstLines[i] >= layouts[i].lineCount) 0 else layouts[i].getLineBottom(ends[i] - 1) - layouts[i].getLineTop(firstLines[i])
                    }.toFloat() + inset * 2
                    val canvas = requireNotNull(page).canvas
                    layouts.forEachIndexed { i, layout ->
                        val x = 48f + cellWidth * i
                        if (row.table) canvas.drawRect(x, y, x + cellWidth, y + height, border)
                        if (firstLines[i] < layout.lineCount) {
                            canvas.save()
                            canvas.clipRect(x + inset, y + inset, x + cellWidth - inset, y + height - inset)
                            canvas.translate(x + inset, y + inset - layout.getLineTop(firstLines[i]))
                            layout.draw(canvas)
                            canvas.restore()
                        }
                        firstLines[i] = ends[i]
                    }
                    y += height
                    val more = layouts.indices.any { firstLines[it] < layouts[it].lineCount }
                    if (more) nextPage()
                } while (more)
                if (!row.table) y += 10f
            }
            page?.let(document::finishPage); page = null
            document.writeTo(output)
        } finally {
            page?.let(document::finishPage)
            document.close()
        }
        return writeArtifact(ctx, args.stringArg("fileName"), ".pdf", "application/pdf", output.toByteArray())
    }
}
