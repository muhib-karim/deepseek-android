package app.dsandroid.tools

import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.TextPaint
import app.dsandroid.core.document.DocBlock
import app.dsandroid.core.markdown.InlineSpan
import app.dsandroid.core.pdf.*
import app.dsandroid.core.tools.*
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*
import kotlin.coroutines.coroutineContext

/** Android metrics and drawing for the :core PDF layout engine. */
class AndroidPdfRenderer : TextMeasurer {
    private val paints = HashMap<Pair<PdfFont, Float>, TextPaint>()

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
    suspend fun pdf(title: String?, markdown: String): ByteArray = render(PdfLayoutEngine(this).layout(title, markdown))

    suspend fun pdf(title: String?, blocks: List<DocBlock>): ByteArray = render(PdfLayoutEngine(this).layout(title, blocks))
}

/**
 * create_pdf {title, markdown, file_path?}: markdown (headings, lists, tables, code, page breaks) laid out by
 * the :core engine and drawn with Android's PdfDocument into the chat folder. The legacy fileName + content
 * (conversation export) and title + blocks inputs remain supported.
 */
class CreatePdfTool : Tool {
    override val name = "create_pdf"
    override val description = "Create a PDF document from Markdown: headings, paragraphs, bold/italic, bullet and numbered lists, tables, " +
        "code blocks, block quotes and page breaks (a line with \\pagebreak). Shown to the user as a file card."
    override val risk = Risk.SAFE
    override val kind = ToolKind.WRITE
    override val schema = objectSchema(mapOf(
        "title" to stringSchema("Document title"),
        "markdown" to stringSchema("Document body in Markdown"),
        "file_path" to stringSchema("Optional file name ending in .pdf"),
    ), listOf("title", "markdown"))

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        val renderer = AndroidPdfRenderer()
        if ("markdown" !in args) return legacy(args, ctx, renderer)
        val title = args.stringArg("title").trim().take(300)
        val layout = PdfLayoutEngine(renderer).layout(title, args.stringArg("markdown"))
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
