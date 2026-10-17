package app.dsandroid.core.tools

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal fun xml(value: String): String {
    val valid = StringBuilder(value.length)
    var index = 0
    while (index < value.length) {
        val character = value[index]
        when {
            Character.isHighSurrogate(character) -> {
                if (index + 1 < value.length && Character.isLowSurrogate(value[index + 1])) {
                    valid.append(character).append(value[++index])
                }
            }
            Character.isLowSurrogate(character) -> Unit
            character == '\n' || character == '\r' || character == '\t' ||
                character.code in 0x20..0xD7FF || character.code in 0xE000..0xFFFD -> valid.append(character)
        }
        index++
    }
    return valid.toString().replace("&", "&amp;").replace("<", "&lt;")
        .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")
}

/** Check before escaping or joining strings: escaping may multiply allocation size. */
private class OfficeTextBudget {
    private var characters = 0L
    fun accept(text: String) {
        require(text.length <= 200_000) { "Office text field exceeds 200,000 characters" }
        characters += text.length
        require(characters <= 1_000_000) { "Office document text exceeds one million characters" }
    }
}
internal const val REL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
internal fun relationships(vararg targets: Pair<String, String>): String = """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""" + targets.mapIndexed { i, p -> """<Relationship Id="rId${i+1}" Type="$REL/${p.first}" Target="${xml(p.second)}"/>""" }.joinToString("") + "</Relationships>"
internal fun officeZip(parts: Map<String, String>, types: Map<String, String>): ByteArray {
    require(parts.values.sumOf { it.length } <= 8 * 1024 * 1024) { "Office document too large" }
    val contentTypes = """<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/>""" + types.entries.joinToString("") { """<Override PartName="/${it.key}" ContentType="application/vnd.openxmlformats-officedocument.${it.value}+xml"/>""" } + "</Types>"
    val output = ByteArrayOutputStream()
    ZipOutputStream(output).use { zip -> (mapOf("[Content_Types].xml" to contentTypes) + parts).forEach { (name, text) -> zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray()); zip.closeEntry() } }
    return output.toByteArray()
}
data class DocxBlock(val heading: String? = null, val paragraph: String? = null, val bullets: List<String>? = null, val table: List<List<String>>? = null)
object DocxBuilder {
    fun build(title: String, blocks: List<DocxBlock>): ByteArray {
        require(blocks.size <= 1000)
        val budget = OfficeTextBudget()
        budget.accept(title)
        blocks.forEach { block ->
            block.heading?.let(budget::accept)
            block.paragraph?.let(budget::accept)
            block.bullets?.let { bullets ->
                require(bullets.size <= 1000)
                bullets.forEach(budget::accept)
            }
            block.table?.let { rows ->
                require(rows.size <= 1000 && rows.all { it.size <= 100 })
                rows.forEach { row -> row.forEach(budget::accept) }
            }
        }
        fun p(text: String, style: String = ""): String = "<w:p>$style<w:r><w:t xml:space=\"preserve\">${xml(text)}</w:t></w:r></w:p>"
        val body = p(title, "<w:pPr><w:pStyle w:val=\"Title\"/></w:pPr>") + blocks.joinToString("") { b ->
            require(listOf(b.heading,b.paragraph,b.bullets,b.table).count { it != null } == 1) { "Each block needs exactly one type" }
            when {
                b.heading != null -> p(b.heading,"<w:pPr><w:pStyle w:val=\"Heading1\"/></w:pPr>")
                b.paragraph != null -> p(b.paragraph)
                b.bullets != null -> b.bullets.joinToString("") { p(it,"<w:pPr><w:numPr><w:ilvl w:val=\"0\"/><w:numId w:val=\"1\"/></w:numPr></w:pPr>") }
                else -> "<w:tbl><w:tblPr/><w:tblGrid/>" + b.table.orEmpty().joinToString("") { row -> "<w:tr>" + row.joinToString("") { "<w:tc>${p(it)}</w:tc>" } + "</w:tr>" } + "</w:tbl>"
            }
        }
        val ns = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
        return officeZip(mapOf(
            "_rels/.rels" to relationships("officeDocument" to "word/document.xml"),
            "word/document.xml" to "<w:document xmlns:w=\"$ns\"><w:body>$body<w:sectPr/></w:body></w:document>",
            "word/_rels/document.xml.rels" to relationships("styles" to "styles.xml", "numbering" to "numbering.xml"),
            "word/styles.xml" to "<w:styles xmlns:w=\"$ns\"><w:style w:type=\"paragraph\" w:styleId=\"Title\"><w:name w:val=\"Title\"/><w:rPr><w:b/><w:sz w:val=\"40\"/></w:rPr></w:style><w:style w:type=\"paragraph\" w:styleId=\"Heading1\"><w:name w:val=\"Heading 1\"/><w:rPr><w:b/><w:sz w:val=\"30\"/></w:rPr></w:style></w:styles>",
            "word/numbering.xml" to "<w:numbering xmlns:w=\"$ns\"><w:abstractNum w:abstractNumId=\"0\"><w:lvl w:ilvl=\"0\"><w:start w:val=\"1\"/><w:numFmt w:val=\"bullet\"/><w:lvlText w:val=\"•\"/></w:lvl></w:abstractNum><w:num w:numId=\"1\"><w:abstractNumId w:val=\"0\"/></w:num></w:numbering>"
        ), mapOf("word/document.xml" to "wordprocessingml.document.main", "word/styles.xml" to "wordprocessingml.styles", "word/numbering.xml" to "wordprocessingml.numbering"))
    }
}
data class Sheet(val name: String, val rows: List<List<String>>)
object XlsxBuilder {
    fun build(sheets: List<Sheet>): ByteArray {
        require(sheets.size in 1..50 && sheets.map { it.name.lowercase() }.distinct().size == sheets.size)
        val budget = OfficeTextBudget()
        var cellCount = 0L
        sheets.forEach { sheet ->
            budget.accept(sheet.name)
            require(sheet.rows.size <= 10000)
            sheet.rows.forEach { row ->
                require(row.size <= 256)
                cellCount += row.size
                require(cellCount <= 100_000) { "Too many spreadsheet cells" }
                row.forEach(budget::accept)
            }
        }
        val ns = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
        val parts = linkedMapOf("_rels/.rels" to relationships("officeDocument" to "xl/workbook.xml"))
        val types = linkedMapOf("xl/workbook.xml" to "spreadsheetml.sheet.main")
        parts["xl/workbook.xml"] = "<workbook xmlns=\"$ns\" xmlns:r=\"$REL\"><sheets>" + sheets.mapIndexed { i,s -> require(s.name.isNotBlank() && s.name.length <= 31 && s.name.none { it in "[]:*?/\\" }); "<sheet name=\"${xml(s.name)}\" sheetId=\"${i+1}\" r:id=\"rId${i+1}\"/>" }.joinToString("") + "</sheets></workbook>"
        parts["xl/_rels/workbook.xml.rels"] = relationships(*sheets.indices.map { "worksheet" to "worksheets/sheet${it+1}.xml" }.toTypedArray())
        sheets.forEachIndexed { index,sheet ->
            require(sheet.rows.size <= 10000 && sheet.rows.all { it.size <= 256 })
            val path = "xl/worksheets/sheet${index+1}.xml"; types[path] = "spreadsheetml.worksheet"
            parts[path] = "<worksheet xmlns=\"$ns\"><sheetData>" + sheet.rows.mapIndexed { i,row -> "<row r=\"${i+1}\">" + row.map { cell ->
                if (cell.matches(Regex("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?")) && cell.toDoubleOrNull()?.isFinite() == true) "<c><v>$cell</v></c>" else "<c t=\"inlineStr\"><is><t xml:space=\"preserve\">${xml(cell)}</t></is></c>"
            }.joinToString("") + "</row>" }.joinToString("") + "</sheetData></worksheet>"
        }
        return officeZip(parts,types)
    }
}
data class Slide(val title: String, val bullets: List<String> = emptyList(), val notes: String? = null)
object PptxBuilder {
    fun build(title: String, slides: List<Slide>): ByteArray {
        require(slides.size <= 100)
        val budget = OfficeTextBudget()
        budget.accept(title)
        slides.forEach { slide ->
            budget.accept(slide.title)
            require(slide.bullets.size <= 100)
            slide.bullets.forEach(budget::accept)
            slide.notes?.let(budget::accept)
        }
        val p = "http://schemas.openxmlformats.org/presentationml/2006/main"; val a = "http://schemas.openxmlformats.org/drawingml/2006/main"
        fun group() = "<p:nvGrpSpPr><p:cNvPr id=\"1\" name=\"\"/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"0\" cy=\"0\"/><a:chOff x=\"0\" y=\"0\"/><a:chExt cx=\"0\" cy=\"0\"/></a:xfrm></p:grpSpPr>"
        fun shape(id: Int, text: List<String>, y: Int, size: Int) = "<p:sp><p:nvSpPr><p:cNvPr id=\"$id\" name=\"Text $id\"/><p:cNvSpPr txBox=\"1\"/><p:nvPr/></p:nvSpPr><p:spPr><a:xfrm><a:off x=\"700000\" y=\"$y\"/><a:ext cx=\"10792000\" cy=\"4000000\"/></a:xfrm><a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></p:spPr><p:txBody><a:bodyPr/><a:lstStyle/>" + text.ifEmpty { listOf("") }.joinToString("") { "<a:p><a:r><a:rPr lang=\"en-US\" sz=\"$size\"/><a:t>${xml(it)}</a:t></a:r></a:p>" } + "</p:txBody></p:sp>"
        fun wrap(tag: String,body: String) = "<p:$tag xmlns:p=\"$p\" xmlns:a=\"$a\" xmlns:r=\"$REL\">$body</p:$tag>"
        val parts = linkedMapOf("_rels/.rels" to relationships("officeDocument" to "ppt/presentation.xml")); val types = linkedMapOf("ppt/presentation.xml" to "presentationml.presentation.main")
        val all = listOf(Slide(title)) + slides
        parts["ppt/presentation.xml"] = wrap("presentation", "<p:sldMasterIdLst><p:sldMasterId id=\"2147483648\" r:id=\"rId1\"/></p:sldMasterIdLst><p:notesMasterIdLst><p:notesMasterId r:id=\"rId2\"/></p:notesMasterIdLst><p:sldIdLst>" + all.indices.joinToString("") { "<p:sldId id=\"${256+it}\" r:id=\"rId${it+3}\"/>" } + "</p:sldIdLst><p:sldSz cx=\"12192000\" cy=\"6858000\" type=\"screen16x9\"/><p:notesSz cx=\"6858000\" cy=\"9144000\"/>")
        parts["ppt/_rels/presentation.xml.rels"] = relationships(* (listOf("slideMaster" to "slideMasters/slideMaster1.xml", "notesMaster" to "notesMasters/notesMaster1.xml") + all.indices.map { "slide" to "slides/slide${it+1}.xml" }).toTypedArray())
        parts["ppt/slideMasters/slideMaster1.xml"] = wrap("sldMaster", "<p:cSld><p:spTree>${group()}</p:spTree></p:cSld><p:clrMap accent1=\"accent1\" accent2=\"accent2\" accent3=\"accent3\" accent4=\"accent4\" accent5=\"accent5\" accent6=\"accent6\" bg1=\"lt1\" bg2=\"lt2\" folHlink=\"folHlink\" hlink=\"hlink\" tx1=\"dk1\" tx2=\"dk2\"/><p:sldLayoutIdLst><p:sldLayoutId id=\"2147483649\" r:id=\"rId1\"/></p:sldLayoutIdLst>")
        parts["ppt/slideMasters/_rels/slideMaster1.xml.rels"] = relationships("slideLayout" to "../slideLayouts/slideLayout1.xml", "theme" to "../theme/theme1.xml")
        parts["ppt/slideLayouts/slideLayout1.xml"] = wrap("sldLayout", "<p:cSld name=\"Blank\"><p:spTree>${group()}</p:spTree></p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr>")
        parts["ppt/slideLayouts/_rels/slideLayout1.xml.rels"] = relationships("slideMaster" to "../slideMasters/slideMaster1.xml")
        parts["ppt/notesMasters/notesMaster1.xml"] = wrap("notesMaster", "<p:cSld><p:spTree>${group()}</p:spTree></p:cSld><p:clrMap accent1=\"accent1\" accent2=\"accent2\" accent3=\"accent3\" accent4=\"accent4\" accent5=\"accent5\" accent6=\"accent6\" bg1=\"lt1\" bg2=\"lt2\" folHlink=\"folHlink\" hlink=\"hlink\" tx1=\"dk1\" tx2=\"dk2\"/>")
        parts["ppt/notesMasters/_rels/notesMaster1.xml.rels"] = relationships("theme" to "../theme/theme1.xml")
        val colors = listOf("dk1" to "121212", "lt1" to "FFFFFF", "dk2" to "24304F", "lt2" to "EEF3FF", "accent1" to "3D63F5", "accent2" to "249F88", "accent3" to "E5A63D", "accent4" to "946BD6", "accent5" to "D56B6B", "accent6" to "4A9EC9", "hlink" to "3D63F5", "folHlink" to "946BD6")
        parts["ppt/theme/theme1.xml"] = "<a:theme xmlns:a=\"$a\" name=\"Simple\"><a:themeElements><a:clrScheme name=\"Simple\">" + colors.joinToString("") { "<a:${it.first}><a:srgbClr val=\"${it.second}\"/></a:${it.first}>" } + "</a:clrScheme><a:fontScheme name=\"Simple\"><a:majorFont><a:latin typeface=\"Arial\"/><a:ea typeface=\"\"/><a:cs typeface=\"\"/></a:majorFont><a:minorFont><a:latin typeface=\"Arial\"/><a:ea typeface=\"\"/><a:cs typeface=\"\"/></a:minorFont></a:fontScheme><a:fmtScheme name=\"Simple\"><a:fillStyleLst><a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill><a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill><a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill></a:fillStyleLst><a:lnStyleLst><a:ln w=\"12700\"><a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill><a:prstDash val=\"solid\"/></a:ln><a:ln w=\"12700\"><a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill><a:prstDash val=\"solid\"/></a:ln><a:ln w=\"12700\"><a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill><a:prstDash val=\"solid\"/></a:ln></a:lnStyleLst><a:effectStyleLst><a:effectStyle><a:effectLst/></a:effectStyle><a:effectStyle><a:effectLst/></a:effectStyle><a:effectStyle><a:effectLst/></a:effectStyle></a:effectStyleLst><a:bgFillStyleLst><a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill><a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill><a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill></a:bgFillStyleLst></a:fmtScheme></a:themeElements></a:theme>"
        types["ppt/slideMasters/slideMaster1.xml"] = "presentationml.slideMaster"; types["ppt/slideLayouts/slideLayout1.xml"] = "presentationml.slideLayout"; types["ppt/notesMasters/notesMaster1.xml"] = "presentationml.notesMaster"; types["ppt/theme/theme1.xml"] = "theme"
        all.forEachIndexed { i,s ->
            val n = i+1; val path = "ppt/slides/slide$n.xml"; types[path] = "presentationml.slide"
            parts[path] = wrap("sld", "<p:cSld><p:spTree>${group()}${shape(2,listOf(s.title),500000,3200)}${shape(3,s.bullets.map { "• $it" },1700000,2200)}</p:spTree></p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr>")
            val rels = mutableListOf("slideLayout" to "../slideLayouts/slideLayout1.xml")
            if (s.notes != null) {
                rels.add("notesSlide" to "../notesSlides/notesSlide$n.xml"); val notesPath = "ppt/notesSlides/notesSlide$n.xml"; types[notesPath] = "presentationml.notesSlide"
                parts[notesPath] = wrap("notes", "<p:cSld><p:spTree>${group()}${shape(2,listOf(s.notes),500000,1600)}</p:spTree></p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr>")
                parts["ppt/notesSlides/_rels/notesSlide$n.xml.rels"] = relationships("notesMaster" to "../notesMasters/notesMaster1.xml", "slide" to "../slides/slide$n.xml")
            }
            parts["ppt/slides/_rels/slide$n.xml.rels"] = relationships(*rels.toTypedArray())
        }
        return officeZip(parts,types)
    }
}
