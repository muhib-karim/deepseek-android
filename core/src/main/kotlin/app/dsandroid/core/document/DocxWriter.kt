package app.dsandroid.core.document

import app.dsandroid.core.markdown.InlineSpan
import app.dsandroid.core.markdown.TableAlignment
import app.dsandroid.core.tools.xml
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Markdown to a minimal valid DOCX package: content types, package and document relationships, core
 * properties, styles (title, three heading levels, list, quote, code) and numbering for bullets and
 * restartable decimal lists. Tables get borders, a repeated shaded header row and column alignment.
 */
object DocxWriter {
    const val MIME = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    const val MAX_BYTES = 10 * 1024 * 1024
    private const val W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    private const val REL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private const val PACKAGE_REL = "http://schemas.openxmlformats.org/package/2006/relationships"
    private const val TEXT_WIDTH = 9026
    private const val BULLET_NUM = 1

    fun build(title: String?, markdown: String): ByteArray = build(title, DocumentModel.parse(markdown))

    fun build(title: String?, blocks: List<DocBlock>): ByteArray {
        val body = StringBuilder()
        val orderedStarts = mutableListOf<Int>()
        var orderedNum: Int? = null
        if (!title.isNullOrBlank()) body.append(paragraph(listOf(InlineSpan(title.trim())), style = "Title"))
        for (block in blocks) {
            if (block !is DocBlock.ListItem) orderedNum = null
            when (block) {
                is DocBlock.Heading -> body.append(paragraph(block.spans, style = "Heading${block.level.coerceIn(1, 3)}"))
                is DocBlock.Paragraph -> body.append(paragraph(block.spans))
                is DocBlock.ListItem -> {
                    val numId = if (block.ordered) {
                        orderedNum ?: (BULLET_NUM + 1 + orderedStarts.size).also { orderedStarts += block.number; orderedNum = it }
                    } else BULLET_NUM
                    body.append(paragraph(block.spans, style = "ListParagraph", numbering = numId to block.depth.coerceIn(0, 5)))
                }
                is DocBlock.Code -> block.text.split('\n').forEach { line ->
                    body.append("<w:p><w:pPr><w:pStyle w:val=\"Code\"/></w:pPr><w:r><w:t xml:space=\"preserve\">${xml(line)}</w:t></w:r></w:p>")
                }
                is DocBlock.Quote -> body.append(paragraph(block.spans, style = "Quote"))
                is DocBlock.Table -> body.append(table(block))
                // Images are embedded in PDFs; DOCX keeps a readable placeholder with the caption.
                is DocBlock.Image -> body.append(paragraph(listOf(InlineSpan("[Image: ${block.alt.ifBlank { block.source }}]", italic = true))))
                DocBlock.Rule -> body.append("<w:p><w:pPr><w:pBdr><w:bottom w:val=\"single\" w:sz=\"6\" w:space=\"1\" w:color=\"BFBFBF\"/></w:pBdr></w:pPr></w:p>")
                DocBlock.PageBreak -> body.append("<w:p><w:r><w:br w:type=\"page\"/></w:r></w:p>")
            }
        }
        val document = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><w:document xmlns:w=\"$W\" xmlns:r=\"$REL\"><w:body>$body" +
            "<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/><w:pgMar w:top=\"1440\" w:right=\"1440\" w:bottom=\"1440\" w:left=\"1440\" w:header=\"708\" w:footer=\"708\" w:gutter=\"0\"/></w:sectPr></w:body></w:document>"
        val parts = linkedMapOf(
            "[Content_Types].xml" to contentTypes(),
            "_rels/.rels" to "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><Relationships xmlns=\"$PACKAGE_REL\">" +
                "<Relationship Id=\"rId1\" Type=\"$REL/officeDocument\" Target=\"word/document.xml\"/>" +
                "<Relationship Id=\"rId2\" Type=\"$PACKAGE_REL/metadata/core-properties\" Target=\"docProps/core.xml\"/></Relationships>",
            "docProps/core.xml" to "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><cp:coreProperties xmlns:cp=\"http://schemas.openxmlformats.org/package/2006/metadata/core-properties\" " +
                "xmlns:dc=\"http://purl.org/dc/elements/1.1/\"><dc:title>${xml(title.orEmpty().trim())}</dc:title><dc:creator>DeepSeek Android</dc:creator></cp:coreProperties>",
            "word/_rels/document.xml.rels" to "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><Relationships xmlns=\"$PACKAGE_REL\">" +
                "<Relationship Id=\"rId1\" Type=\"$REL/styles\" Target=\"styles.xml\"/><Relationship Id=\"rId2\" Type=\"$REL/numbering\" Target=\"numbering.xml\"/></Relationships>",
            "word/document.xml" to document,
            "word/styles.xml" to styles(),
            "word/numbering.xml" to numbering(orderedStarts),
        )
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            parts.forEach { (name, text) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        require(output.size() <= MAX_BYTES) { "DOCX exceeds 10 MB" }
        return output.toByteArray()
    }

    private fun paragraph(spans: List<InlineSpan>, style: String? = null, numbering: Pair<Int, Int>? = null, align: TableAlignment? = null): String {
        val properties = buildString {
            style?.let { append("<w:pStyle w:val=\"$it\"/>") }
            numbering?.let { (numId, level) -> append("<w:numPr><w:ilvl w:val=\"$level\"/><w:numId w:val=\"$numId\"/></w:numPr>") }
            when (align) {
                TableAlignment.CENTER -> append("<w:jc w:val=\"center\"/>")
                TableAlignment.RIGHT -> append("<w:jc w:val=\"right\"/>")
                else -> Unit
            }
        }
        val runs = spans.joinToString("") { run(it) }
        return "<w:p>${if (properties.isEmpty()) "" else "<w:pPr>$properties</w:pPr>"}$runs</w:p>"
    }

    private fun run(span: InlineSpan): String {
        if (span.text.isEmpty()) return ""
        val properties = buildString {
            if (span.code) append("<w:rFonts w:ascii=\"Courier New\" w:hAnsi=\"Courier New\" w:cs=\"Courier New\"/>")
            if (span.bold) append("<w:b/>")
            if (span.italic) append("<w:i/>")
            if (span.strikethrough) append("<w:strike/>")
            if (span.link != null) append("<w:color w:val=\"2E5BD8\"/>")
            if (span.link != null) append("<w:u w:val=\"single\"/>")
        }
        val text = span.text.split('\n').joinToString("<w:br/>") { "<w:t xml:space=\"preserve\">${xml(it)}</w:t>" }
        return "<w:r>${if (properties.isEmpty()) "" else "<w:rPr>$properties</w:rPr>"}$text</w:r>"
    }

    private fun table(block: DocBlock.Table): String {
        val columns = maxOf(block.header.size, block.rows.maxOfOrNull { it.size } ?: 0)
        if (columns == 0) return ""
        val width = TEXT_WIDTH / columns
        val border = { side: String -> "<w:$side w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"BFBFBF\"/>" }
        fun row(cells: List<List<InlineSpan>>, header: Boolean): String = "<w:tr>${if (header) "<w:trPr><w:tblHeader/></w:trPr>" else ""}" +
            (0 until columns).joinToString("") { column ->
                val spans = cells.getOrElse(column) { emptyList() }.map { if (header) it.copy(bold = true) else it }
                "<w:tc><w:tcPr><w:tcW w:w=\"$width\" w:type=\"dxa\"/>${if (header) "<w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"EEF2F8\"/>" else ""}</w:tcPr>" +
                    paragraph(spans, align = block.alignments.getOrNull(column)) + "</w:tc>"
            } + "</w:tr>"
        return "<w:tbl><w:tblPr><w:tblW w:w=\"$TEXT_WIDTH\" w:type=\"dxa\"/><w:tblBorders>" +
            listOf("top", "left", "bottom", "right", "insideH", "insideV").joinToString("") { border(it) } +
            "</w:tblBorders></w:tblPr><w:tblGrid>" + "<w:gridCol w:w=\"$width\"/>".repeat(columns) + "</w:tblGrid>" +
            row(block.header, true) + block.rows.joinToString("") { row(it, false) } + "</w:tbl>" +
            // Word requires a paragraph between adjacent tables and before the section properties.
            "<w:p/>"
    }

    private fun contentTypes() = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
        "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
        "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
        "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>" +
        "<Override PartName=\"/word/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml\"/>" +
        "<Override PartName=\"/word/numbering.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.numbering+xml\"/>" +
        "<Override PartName=\"/docProps/core.xml\" ContentType=\"application/vnd.openxmlformats-package.core-properties+xml\"/></Types>"

    private fun styles(): String {
        fun heading(level: Int, size: Int) = "<w:style w:type=\"paragraph\" w:styleId=\"Heading$level\"><w:name w:val=\"heading $level\"/><w:basedOn w:val=\"Normal\"/>" +
            "<w:next w:val=\"Normal\"/><w:qFormat/><w:pPr><w:keepNext/><w:spacing w:before=\"240\" w:after=\"120\"/><w:outlineLvl w:val=\"${level - 1}\"/></w:pPr>" +
            "<w:rPr><w:b/><w:sz w:val=\"$size\"/></w:rPr></w:style>"
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><w:styles xmlns:w=\"$W\">" +
            "<w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii=\"Calibri\" w:hAnsi=\"Calibri\" w:eastAsia=\"Calibri\" w:cs=\"Calibri\"/><w:sz w:val=\"22\"/><w:szCs w:val=\"22\"/></w:rPr></w:rPrDefault>" +
            "<w:pPrDefault><w:pPr><w:spacing w:after=\"120\" w:line=\"276\" w:lineRule=\"auto\"/></w:pPr></w:pPrDefault></w:docDefaults>" +
            "<w:style w:type=\"paragraph\" w:default=\"1\" w:styleId=\"Normal\"><w:name w:val=\"Normal\"/><w:qFormat/></w:style>" +
            "<w:style w:type=\"paragraph\" w:styleId=\"Title\"><w:name w:val=\"Title\"/><w:basedOn w:val=\"Normal\"/><w:next w:val=\"Normal\"/><w:qFormat/>" +
            "<w:pPr><w:spacing w:after=\"240\"/></w:pPr><w:rPr><w:b/><w:sz w:val=\"44\"/></w:rPr></w:style>" +
            heading(1, 32) + heading(2, 28) + heading(3, 24) +
            "<w:style w:type=\"paragraph\" w:styleId=\"ListParagraph\"><w:name w:val=\"List Paragraph\"/><w:basedOn w:val=\"Normal\"/><w:qFormat/>" +
            "<w:pPr><w:spacing w:after=\"60\"/><w:contextualSpacing/></w:pPr></w:style>" +
            "<w:style w:type=\"paragraph\" w:styleId=\"Quote\"><w:name w:val=\"Quote\"/><w:basedOn w:val=\"Normal\"/><w:qFormat/>" +
            "<w:pPr><w:ind w:left=\"567\"/></w:pPr><w:rPr><w:i/><w:color w:val=\"595959\"/></w:rPr></w:style>" +
            "<w:style w:type=\"paragraph\" w:styleId=\"Code\"><w:name w:val=\"Code\"/><w:basedOn w:val=\"Normal\"/>" +
            "<w:pPr><w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"F3F4F6\"/><w:spacing w:after=\"0\" w:line=\"240\" w:lineRule=\"auto\"/></w:pPr>" +
            "<w:rPr><w:rFonts w:ascii=\"Courier New\" w:hAnsi=\"Courier New\" w:cs=\"Courier New\"/><w:sz w:val=\"19\"/></w:rPr></w:style></w:styles>"
    }

    /** Abstract 0 is bullets and 1 is decimal; each ordered list gets its own num so numbering restarts. */
    private fun numbering(orderedStarts: List<Int>): String {
        val bullets = listOf("\u2022", "\u25E6", "\u25AA")
        fun levels(ordered: Boolean) = (0..5).joinToString("") { level ->
            "<w:lvl w:ilvl=\"$level\"><w:start w:val=\"1\"/><w:numFmt w:val=\"${if (ordered) "decimal" else "bullet"}\"/>" +
                "<w:lvlText w:val=\"${if (ordered) "%${level + 1}." else bullets[level % bullets.size]}\"/><w:lvlJc w:val=\"left\"/>" +
                "<w:pPr><w:ind w:left=\"${720 * (level + 1)}\" w:hanging=\"360\"/></w:pPr></w:lvl>"
        }
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><w:numbering xmlns:w=\"$W\">" +
            "<w:abstractNum w:abstractNumId=\"0\"><w:multiLevelType w:val=\"hybridMultilevel\"/>${levels(false)}</w:abstractNum>" +
            "<w:abstractNum w:abstractNumId=\"1\"><w:multiLevelType w:val=\"hybridMultilevel\"/>${levels(true)}</w:abstractNum>" +
            "<w:num w:numId=\"$BULLET_NUM\"><w:abstractNumId w:val=\"0\"/></w:num>" +
            orderedStarts.mapIndexed { index, start ->
                "<w:num w:numId=\"${BULLET_NUM + 1 + index}\"><w:abstractNumId w:val=\"1\"/><w:lvlOverride w:ilvl=\"0\"><w:startOverride w:val=\"$start\"/></w:lvlOverride></w:num>"
            }.joinToString("") + "</w:numbering>"
    }
}
