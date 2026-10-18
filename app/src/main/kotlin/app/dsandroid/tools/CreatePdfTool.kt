package app.dsandroid.tools

import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.TextPaint
import app.dsandroid.core.document.DocBlock
import app.dsandroid.core.markdown.InlineSpan
import app.dsandroid.core.pdf.*
import app.dsandroid.core.tools.*
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*
import kotlin.coroutines.coroutineContext

/** Android metrics and drawing for the :core PDF layout engine; [images] maps Markdown image paths to files. */
class AndroidPdfRenderer(private val images: (String) -> File? = { null }) : TextMeasurer {
    private val paints = HashMap<Pair<PdfFont, Float>, TextPaint>()

    fun imageSize(source: String): Pair<Int, Int>? {
        val file = images(source) ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        return if (bounds.outWidth > 0 && bounds.outHeight > 0) bounds.outWidth to bounds.outHeight else null
    }

    /** Decodes at about twice the drawn size, enough for print without holding full-resolution bitmaps. */
    private fun drawImage(canvas: Canvas, op: PdfOp.Image) {
        val file = images(op.source) ?: return
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= op.width * 2 && bounds.outHeight / (sample * 2) >= op.height * 2) sample *= 2
        val bitmap = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return
        try {
            canvas.drawBitmap(bitmap, null, RectF(op.x, op.y, op.x + op.width, op.y + op.height), Paint(Paint.FILTER_BITMAP_FLAG))
        } finally {
            bitmap.recycle()
        }
    }

    private fun paint(font: PdfFont, size: Float): TextPaint = paints.getOrPut(font to size) {
        TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size
            typeface = when (font) {
                PdfFont.REGULAR -> Typeface.DEFAULT
                PdfFont.BOLD -> Typeface.DEFAULT_BOLD
                PdfFont.ITALIC -> Typeface.create(Typeface.DEFAULT, Typeface.ITALIC)
                PdfFont.BOLD_ITALIC -> Typeface.create(Typeface.DEFAULT, Typeface.BOLD_ITALIC)
                PdfFont.MONO -> Typeface.MONOSPACE
            }
        }
    }

    override fun width(text: String, font: PdfFont, size: Float): Float = paint(font, size).measureText(text)

    suspend fun render(layout: PdfLayout): ByteArray {
        val document = PdfDocument()
        var page: PdfDocument.Page? = null
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 0.5f }
        try {
            for (content in layout.pages) {
                coroutineContext.ensureActive()
                val current = document.startPage(PdfDocument.PageInfo.Builder(layout.width.toInt(), layout.height.toInt(), content.number).create())
                page = current
                val canvas = current.canvas
                for (op in content.ops) when (op) {
                    is PdfOp.Text -> canvas.drawText(op.text, op.x, op.baseline, paint(op.font, op.size).apply { color = op.color.toInt() })
                    is PdfOp.Box -> {
                        op.fill?.let { fill.color = it.toInt(); canvas.drawRect(op.x, op.y, op.x + op.width, op.y + op.height, fill) }
                        op.stroke?.let { stroke.color = it.toInt(); canvas.drawRect(op.x, op.y, op.x + op.width, op.y + op.height, stroke) }
                    }
                    is PdfOp.Line -> {
                        stroke.color = op.color.toInt()
                        stroke.strokeWidth = op.width
                        canvas.drawLine(op.x1, op.y1, op.x2, op.y2, stroke)
                        stroke.strokeWidth = 0.5f
                    }
                    is PdfOp.Image -> drawImage(canvas, op)
                }
                document.finishPage(current)
                page = null
            }
            val output = ByteArrayOutputStream()
            document.writeTo(output)
            return output.toByteArray()
        } finally {
            page?.let(document::finishPage)
            document.close()
        }
    }

    /** Lays out and renders markdown to PDF bytes. */
    suspend fun pdf(title: String?, markdown: String): ByteArray = render(PdfLayoutEngine(this, imageSize = ::imageSize).layout(title, markdown))

    suspend fun pdf(title: String?, blocks: List<DocBlock>): ByteArray = render(PdfLayoutEngine(this, imageSize = ::imageSize).layout(title, blocks))
}

/**
 * create_pdf {title, markdown, file_path?}: markdown (headings, lists, tables, code, page breaks) laid out by
 * the :core engine and drawn with Android's PdfDocument into the chat folder. The legacy fileName + content
 * (conversation export) and title + blocks inputs remain supported.
 */
class CreatePdfTool : Tool {
    override val name = "create_pdf"
    override val description = "Create a PDF document from Markdown: headings, paragraphs, bold/italic, bullet and numbered lists, tables, " +
        "code blocks, block quotes, page breaks (a line with \\pagebreak) and images from this chat's folder on their own line, " +
        "e.g. ![Revenue by month](chart.png) after making chart.png with run_python. Shown to the user as a file card."
    override val risk = Risk.SAFE
    override val kind = ToolKind.WRITE
    override val schema = objectSchema(mapOf(
        "title" to stringSchema("Document title"),
        "markdown" to stringSchema("Document body in Markdown"),
        "file_path" to stringSchema("Optional file name ending in .pdf"),
    ), listOf("title", "markdown"))

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        if ("markdown" !in args) return legacy(args, ctx, AndroidPdfRenderer())
        // Images may only come from this chat's folder (chatFile refuses traversal and absolute paths).
        val renderer = AndroidPdfRenderer { source ->
            runCatching { ctx.chatFile(source) }.getOrNull()?.takeIf { it.isFile && it.length() <= 20L * 1024 * 1024 }
        }
        val title = args.stringArg("title").trim().take(300)
        val layout = PdfLayoutEngine(renderer, imageSize = renderer::imageSize).layout(title, args.stringArg("markdown"))
        val bytes = renderer.render(layout)
        require(bytes.size <= 20 * 1024 * 1024) { "PDF exceeds 20 MB" }
        val requested = args.optionalString("file_path")?.trim()?.takeIf { it.isNotEmpty() } ?: "${slug(title)}.pdf"
        val file = uniqueFile(ctx.chatFile(if (requested.endsWith(".pdf", ignoreCase = true)) requested else "$requested.pdf"))
        writeNewFile(file, bytes)
        return ToolResult("Created ${ctx.chatPath(file)} (${layout.pageCount} page${if (layout.pageCount == 1) "" else "s"})",
            artifacts = listOf(ctx.artifactFor(file)))
    }

    /** Export and older structured calls: fileName at the workspace root, never overwriting. */
    private suspend fun legacy(args: JsonObject, ctx: ToolContext, renderer: AndroidPdfRenderer): ToolResult {
        val rows = PdfContent.rows(args)
        val blocks = rows.drop(if ("content" in args) 0 else 1).fold(mutableListOf<DocBlock>()) { blocks, row ->
            when {
                row.table -> {
                    val last = blocks.lastOrNull() as? DocBlock.Table
                    val cells = row.cells.map { listOf(InlineSpan(it)) }
                    if (last == null) blocks += DocBlock.Table(cells, emptyList(), emptyList())
                    else blocks[blocks.lastIndex] = last.copy(rows = last.rows + listOf(cells))
                }
                row.heading -> blocks += DocBlock.Heading(2, listOf(InlineSpan(row.cells.single())))
                else -> blocks += DocBlock.Paragraph(listOf(InlineSpan(row.cells.single())))
            }
            blocks
        }
        val title = if ("content" in args) null else rows.firstOrNull()?.cells?.singleOrNull()
        return writeArtifact(ctx, args.stringArg("fileName"), ".pdf", "application/pdf", renderer.pdf(title, blocks))
    }

    private fun slug(title: String): String = title.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(60).ifEmpty { "document" }
}
