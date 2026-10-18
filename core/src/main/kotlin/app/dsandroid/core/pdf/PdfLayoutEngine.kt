package app.dsandroid.core.pdf

import app.dsandroid.core.document.DocBlock
import app.dsandroid.core.document.DocumentModel
import app.dsandroid.core.markdown.InlineSpan
import app.dsandroid.core.markdown.TableAlignment

enum class PdfFont { REGULAR, BOLD, ITALIC, BOLD_ITALIC, MONO }

/** Text metrics come from the platform renderer (Android Paint) or a deterministic approximation in tests. */
fun interface TextMeasurer {
    fun width(text: String, font: PdfFont, size: Float): Float
}

/** Average glyph widths: deterministic for JVM tests and a safe fallback. */
object ApproximateMeasurer : TextMeasurer {
    override fun width(text: String, font: PdfFont, size: Float): Float = text.length * size * when (font) {
        PdfFont.MONO -> 0.6f
        PdfFont.BOLD, PdfFont.BOLD_ITALIC -> 0.56f
        else -> 0.5f
    }
}

/** Drawing operations in PDF points, origin at the top-left; colors are ARGB. */
sealed interface PdfOp {
    data class Text(val x: Float, val baseline: Float, val text: String, val font: PdfFont, val size: Float, val color: Long) : PdfOp
    data class Box(val x: Float, val y: Float, val width: Float, val height: Float, val fill: Long?, val stroke: Long?) : PdfOp
    data class Line(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val color: Long, val width: Float) : PdfOp
    /** [source] is the image path from the Markdown; the renderer resolves it the same way the layout did. */
    data class Image(val x: Float, val y: Float, val width: Float, val height: Float, val source: String) : PdfOp
}

data class PdfPage(val number: Int, val ops: List<PdfOp>)

data class PdfLayout(val width: Float, val height: Float, val pages: List<PdfPage>) {
    val pageCount: Int get() = pages.size
}

/** A4 portrait by default. */
data class PdfPageSpec(val width: Float = 595f, val height: Float = 842f, val margin: Float = 50f) {
    val contentWidth: Float get() = width - 2 * margin
}

/**
 * Markdown to paginated drawing operations: headings, paragraphs with bold/italic/code runs, nested
 * bullet and numbered lists, quotes, code blocks, tables with repeated headers, rules and explicit
 * page breaks. Long words and code lines are broken to fit; rows taller than a page are split.
 */
class PdfLayoutEngine(
    private val measurer: TextMeasurer,
    private val spec: PdfPageSpec = PdfPageSpec(),
    /** Pixel size of an image source, or null when it cannot be used. */
    private val imageSize: (String) -> Pair<Int, Int>? = { null },
) {
    companion object {
        const val MAX_PAGES = 300
        const val INK = 0xFF1A1A1AL
        const val MUTED = 0xFF6B6F76L
        const val ACCENT = 0xFF2E5BD8L
        const val RULE = 0xFFD5D8DEL
        const val CODE_FILL = 0xFFF3F4F6L
        const val HEADER_FILL = 0xFFEEF2F8L
        const val BODY = 11f
        const val LEADING = 1.4f
        private val HEADING_SIZES = floatArrayOf(18f, 15f, 13f, 12f, 11.5f, 11f)
        private val BULLETS = listOf("\u2022", "\u25E6", "\u25AA")
        private val TOKENS = Regex("\n|[ ]+|[^ \n]+")
    }

    fun layout(title: String?, markdown: String): PdfLayout = layout(title, DocumentModel.parse(markdown))

    fun layout(title: String?, blocks: List<DocBlock>): PdfLayout = Session().run(title, blocks)

    private data class Run(val text: String, val font: PdfFont, val size: Float, val color: Long)
    private class Piece(val text: String, val font: PdfFont, val size: Float, val color: Long, val width: Float)
    private class TextLine(val pieces: List<Piece>, val width: Float, val size: Float) {
        val height: Float get() = size * LEADING
    }

    private inner class Session {
        private val pages = mutableListOf<MutableList<PdfOp>>()
        private var y = 0f
        private val top get() = spec.margin
        private val bottom get() = spec.height - spec.margin
        private val left get() = spec.margin
        private val width get() = spec.contentWidth
        private val ops: MutableList<PdfOp> get() = pages.last()

        fun run(title: String?, blocks: List<DocBlock>): PdfLayout {
            newPage()
            if (!title.isNullOrBlank()) {
                paragraph(listOf(Run(clean(title.trim()), PdfFont.BOLD, 22f, INK)), spaceAfter = 4f)
                ops += PdfOp.Line(left, y + 2f, left + width, y + 2f, RULE, 0.8f)
                y += 14f
            }
            blocks.forEachIndexed { index, block ->
                val next = blocks.getOrNull(index + 1)
                when (block) {
                    is DocBlock.Heading -> heading(block)
                    is DocBlock.Paragraph -> paragraph(runs(block.spans, BODY), spaceAfter = 7f)
                    is DocBlock.ListItem -> listItem(block, last = next !is DocBlock.ListItem)
                    is DocBlock.Quote -> quote(block)
                    is DocBlock.Code -> code(block)
                    is DocBlock.Table -> table(block)
                    is DocBlock.Image -> image(block)
                    DocBlock.Rule -> rule()
                    DocBlock.PageBreak -> if (y > top) newPage()
                }
            }
            val total = pages.size
            pages.forEachIndexed { index, page ->
                val label = "${index + 1} / $total"
                page += PdfOp.Text((spec.width - measurer.width(label, PdfFont.REGULAR, 8f)) / 2, spec.height - spec.margin / 2,
                    label, PdfFont.REGULAR, 8f, MUTED)
            }
            return PdfLayout(spec.width, spec.height, pages.mapIndexed { index, page -> PdfPage(index + 1, page.toList()) })
        }

        private fun newPage() {
            require(pages.size < MAX_PAGES) { "PDF exceeds $MAX_PAGES pages" }
            pages += mutableListOf<PdfOp>()
            y = top
        }

        /** Starts a new page when [height] does not fit, unless the page is still empty. */
        private fun ensure(height: Float) { if (y + height > bottom && y > top) newPage() }

        private fun heading(block: DocBlock.Heading) {
            val size = HEADING_SIZES[(block.level - 1).coerceIn(0, HEADING_SIZES.lastIndex)]
            val lines = wrap(runs(block.spans, size, PdfFont.BOLD), width)
            if (y > top) y += if (block.level <= 2) 10f else 6f
            // Keep a heading with at least two lines of what follows.
            ensure(lines.sumOf { it.height.toDouble() }.toFloat() + BODY * LEADING * 2)
            lines.forEach { line -> ensure(line.height); draw(line, left, y); y += line.height }
            y += 4f
        }

        private fun paragraph(runs: List<Run>, indent: Float = 0f, spaceAfter: Float) {
            wrap(runs, width - indent).forEach { line -> ensure(line.height); draw(line, left + indent, y); y += line.height }
            y += spaceAfter
        }

        private fun listItem(block: DocBlock.ListItem, last: Boolean) {
            val marker = if (block.ordered) "${block.number}." else BULLETS[block.depth % BULLETS.size]
            val indent = block.depth * 16f
            val markerWidth = maxOf(14f, measurer.width(marker, PdfFont.REGULAR, BODY) + 5f)
            val lines = wrap(runs(block.spans, BODY), width - indent - markerWidth)
            lines.forEachIndexed { index, line ->
                ensure(line.height)
                if (index == 0) ops += PdfOp.Text(left + indent, baseline(y, line.size), marker, PdfFont.REGULAR, BODY, INK)
                draw(line, left + indent + markerWidth, y)
                y += line.height
            }
            y += if (last) 7f else 2f
        }

        private fun quote(block: DocBlock.Quote) {
            wrap(runs(block.spans, BODY, PdfFont.ITALIC, MUTED), width - 14f).forEach { line ->
                ensure(line.height)
                ops += PdfOp.Line(left + 3f, y, left + 3f, y + line.height, RULE, 2.5f)
                draw(line, left + 14f, y)
                y += line.height
            }
            y += 7f
        }

        private fun code(block: DocBlock.Code) {
            val size = 9.5f
            val pad = 6f
            val lineHeight = size * 1.35f
            val chunks = block.text.split('\n').flatMap { wrapChars(it, PdfFont.MONO, size, width - 2 * pad) }
            ensure(lineHeight + 2 * pad)
            var segmentTop = y
            var boxIndex = ops.size
            y += pad
            for (chunk in chunks) {
                if (y + lineHeight > bottom - pad) {
                    ops.add(boxIndex, PdfOp.Box(left, segmentTop, width, y + pad - segmentTop, CODE_FILL, null))
                    newPage()
                    segmentTop = y
                    boxIndex = ops.size
                    y += pad
                }
                if (chunk.isNotBlank()) ops += PdfOp.Text(left + pad, y + size, chunk, PdfFont.MONO, size, INK)
                y += lineHeight
            }
            y += pad
            ops.add(boxIndex, PdfOp.Box(left, segmentTop, width, y - segmentTop, CODE_FILL, null))
            y += 8f
        }

        /** Scaled to the content width (at most 60% of the page height), kept on one page, with an optional caption. */
        private fun image(block: DocBlock.Image) {
            val size = imageSize(block.source)?.takeIf { (w, h) -> w > 0 && h > 0 }
            if (size == null) {
                paragraph(listOf(Run("[Image not available: ${clean(block.source).take(120)}]", PdfFont.ITALIC, BODY, MUTED)), spaceAfter = 7f)
                return
            }
            val maxHeight = (bottom - top) * 0.6f
            val scale = minOf(width / size.first, maxHeight / size.second, 1.5f)
            val drawWidth = size.first * scale
            val drawHeight = size.second * scale
            ensure(drawHeight + if (block.alt.isBlank()) 0f else BODY * LEADING)
            ops += PdfOp.Image(left + (width - drawWidth) / 2, y, drawWidth, drawHeight, block.source)
            y += drawHeight + 4f
            if (block.alt.isNotBlank()) {
                wrap(listOf(Run(clean(block.alt), PdfFont.ITALIC, 9.5f, MUTED)), width).forEach { line ->
                    ensure(line.height)
                    draw(line, left + (width - line.width) / 2, y)
                    y += line.height
                }
            }
            y += 8f
        }

        private fun rule() {
            ensure(14f)
            ops += PdfOp.Line(left, y + 7f, left + width, y + 7f, RULE, 0.8f)
            y += 14f
        }

        private fun table(block: DocBlock.Table) {
            val columns = maxOf(block.header.size, block.rows.maxOfOrNull { it.size } ?: 0)
            if (columns == 0) return
            val size = 9.5f
            val pad = 4f
            val header = (0 until columns).map { runs(block.header.getOrElse(it) { emptyList() }, size, PdfFont.BOLD) }
            val body = block.rows.map { row -> (0 until columns).map { runs(row.getOrElse(it) { emptyList() }, size) } }
            val natural = FloatArray(columns) { column ->
                val widest = (listOf(header[column]) + body.map { it[column] }).maxOf { cell ->
                    cell.sumOf { run -> measurer.width(run.text, run.font, run.size).toDouble() }.toFloat()
                }
                (widest + 2 * pad).coerceIn(24f, width)
            }
            val widths = columnWidths(natural)
            val alignments = (0 until columns).map { block.alignments.getOrElse(it) { TableAlignment.LEFT } }
            fun lines(cells: List<List<Run>>) = cells.mapIndexed { column, cell -> wrap(cell, widths[column] - 2 * pad) }
            val headerLines = lines(header)
            fun height(cells: List<List<TextLine>>) = cells.maxOf { cell -> cell.sumOf { it.height.toDouble() }.toFloat() } + 2 * pad
            fun place(cells: List<List<TextLine>>, isHeader: Boolean) {
                val next = IntArray(columns)
                while (true) {
                    val available = bottom - y - 2 * pad
                    val ends = IntArray(columns) { column ->
                        var used = 0f
                        var end = next[column]
                        while (end < cells[column].size && used + cells[column][end].height <= available) { used += cells[column][end].height; end++ }
                        end
                    }
                    if ((0 until columns).none { ends[it] > next[it] }) {
                        if (y > top) {
                            newPage()
                            if (!isHeader) place(headerLines, true)
                            continue
                        }
                        // A single line taller than an empty page: draw one line per cell anyway.
                        for (column in 0 until columns) if (next[column] < cells[column].size) ends[column] = next[column] + 1
                    }
                    val rowHeight = (0 until columns).maxOf { column ->
                        (next[column] until ends[column]).sumOf { cells[column][it].height.toDouble() }.toFloat()
                    } + 2 * pad
                    var x = left
                    for (column in 0 until columns) {
                        ops += PdfOp.Box(x, y, widths[column], rowHeight, if (isHeader) HEADER_FILL else null, RULE)
                        var lineTop = y + pad
                        for (index in next[column] until ends[column]) {
                            val line = cells[column][index]
                            val free = widths[column] - 2 * pad - line.width
                            val offset = when (alignments[column]) {
                                TableAlignment.LEFT -> 0f
                                TableAlignment.CENTER -> free / 2
                                TableAlignment.RIGHT -> free
                            }.coerceAtLeast(0f)
                            draw(line, x + pad + offset, lineTop)
                            lineTop += line.height
                        }
                        x += widths[column]
                        next[column] = ends[column]
                    }
                    y += rowHeight
                    if ((0 until columns).all { next[it] >= cells[it].size }) return
                    newPage()
                    if (!isHeader) place(headerLines, true)
                }
            }
            val firstRow = body.firstOrNull()?.let { lines(it) }
            ensure(height(headerLines) + (firstRow?.let { row -> row.maxOf { cell -> cell.firstOrNull()?.height ?: 0f } + 2 * pad } ?: 0f))
            place(headerLines, true)
            body.forEachIndexed { index, row -> place(if (index == 0 && firstRow != null) firstRow else lines(row), false) }
            y += 8f
        }

        /** Water-filling: narrow columns keep their natural width, wide ones share what remains. */
        private fun columnWidths(natural: FloatArray): List<Float> {
            val widths = natural.copyOf()
            if (natural.sum() > width) {
                val flexible = natural.indices.toMutableSet()
                var available = width
                while (flexible.isNotEmpty()) {
                    val share = available / flexible.size
                    val narrow = flexible.filter { natural[it] <= share }
                    if (narrow.isEmpty()) { flexible.forEach { widths[it] = share }; break }
                    narrow.forEach { widths[it] = natural[it]; available -= natural[it]; flexible.remove(it) }
                }
            }
            return widths.toList()
        }

        private fun baseline(lineTop: Float, size: Float) = lineTop + size * 1.05f

        private fun draw(line: TextLine, x: Float, lineTop: Float) {
            var cursor = x
            val base = baseline(lineTop, line.size)
            for (piece in line.pieces) {
                if (piece.text.isNotBlank()) ops += PdfOp.Text(cursor, base, piece.text, piece.font, piece.size, piece.color)
                cursor += piece.width
            }
        }
    }

    private fun runs(spans: List<InlineSpan>, size: Float, base: PdfFont = PdfFont.REGULAR, color: Long = INK): List<Run> = spans.map { span ->
        val bold = span.bold || base == PdfFont.BOLD || base == PdfFont.BOLD_ITALIC
        val italic = span.italic || base == PdfFont.ITALIC || base == PdfFont.BOLD_ITALIC
        val font = when {
            span.code -> PdfFont.MONO
            bold && italic -> PdfFont.BOLD_ITALIC
            bold -> PdfFont.BOLD
            italic -> PdfFont.ITALIC
            else -> PdfFont.REGULAR
        }
        Run(clean(span.text), font, if (span.code) size * 0.92f else size, if (span.link != null) ACCENT else color)
    }

    /** Greedy word wrap over styled runs; explicit newlines break lines and over-long words are split. */
    private fun wrap(runs: List<Run>, maxWidth: Float): List<TextLine> {
        val lines = mutableListOf<TextLine>()
        var current = mutableListOf<Piece>()
        var currentWidth = 0f
        var pendingSpace: Piece? = null
        val fallbackSize = runs.firstOrNull()?.size ?: BODY
        fun push() {
            lines += TextLine(merge(current), currentWidth, current.maxOfOrNull { it.size } ?: fallbackSize)
            current = mutableListOf()
            currentWidth = 0f
            pendingSpace = null
        }
        for (run in runs) {
            for (match in TOKENS.findAll(run.text)) {
                val token = match.value
                when {
                    token == "\n" -> push()
                    token.isBlank() -> if (current.isNotEmpty()) pendingSpace = Piece(" ", run.font, run.size, run.color, measurer.width(" ", run.font, run.size))
                    else -> {
                        var word = token
                        var wordWidth = measurer.width(word, run.font, run.size)
                        val space = pendingSpace
                        pendingSpace = null
                        if (current.isNotEmpty() && currentWidth + (space?.width ?: 0f) + wordWidth > maxWidth) push()
                        else if (space != null && current.isNotEmpty()) { current.add(space); currentWidth += space.width }
                        while (wordWidth > maxWidth - currentWidth && word.length > 1) {
                            if (current.isNotEmpty()) { push(); continue }
                            val count = fitCount(word, run.font, run.size, maxWidth).coerceIn(1, word.length - 1)
                            val head = word.substring(0, count)
                            current.add(Piece(head, run.font, run.size, run.color, measurer.width(head, run.font, run.size)))
                            currentWidth += current.last().width
                            push()
                            word = word.substring(count)
                            wordWidth = measurer.width(word, run.font, run.size)
                        }
                        current.add(Piece(word, run.font, run.size, run.color, wordWidth))
                        currentWidth += wordWidth
                    }
                }
            }
        }
        if (current.isNotEmpty() || lines.isEmpty()) push()
        return lines
    }

    private fun wrapChars(text: String, font: PdfFont, size: Float, maxWidth: Float): List<String> {
        val out = mutableListOf<String>()
        var rest = clean(text)
        while (rest.isNotEmpty()) {
            if (measurer.width(rest, font, size) <= maxWidth) { out += rest; break }
            val count = fitCount(rest, font, size, maxWidth).coerceIn(1, rest.length)
            out += rest.substring(0, count)
            rest = rest.substring(count)
        }
        return out.ifEmpty { listOf("") }
    }

    /** Largest prefix length that fits, never splitting a surrogate pair. */
    private fun fitCount(text: String, font: PdfFont, size: Float, maxWidth: Float): Int {
        var low = 0
        var high = text.length
        while (low < high) {
            val middle = (low + high + 1) / 2
            if (measurer.width(text.substring(0, middle), font, size) <= maxWidth) low = middle else high = middle - 1
        }
        if (low in 1 until text.length && Character.isHighSurrogate(text[low - 1])) low--
        return low
    }

    private fun merge(pieces: List<Piece>): List<Piece> {
        val merged = mutableListOf<Piece>()
        for (piece in pieces) {
            val last = merged.lastOrNull()
            if (last != null && last.font == piece.font && last.size == piece.size && last.color == piece.color) {
                merged[merged.lastIndex] = Piece(last.text + piece.text, piece.font, piece.size, piece.color, last.width + piece.width)
            } else merged += piece
        }
        return merged
    }

    /** Drop control characters other than newlines; tabs become spaces. */
    private fun clean(text: String): String = buildString(text.length) {
        for (char in text) when {
            char == '\n' -> append('\n')
            char == '\t' -> append("    ")
            char.isISOControl() -> Unit
            else -> append(char)
        }
    }
}
