package app.dsandroid.core.attachments

import java.io.InputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Document

/** Bounded OOXML reader shared by spreadsheet and presentation previews. Never resolves external entities. */
object OfficeXml {
    fun parse(bytes: ByteArray): Document {
        val text = when {
            bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() -> bytes.toString(Charsets.UTF_16)
            bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() -> bytes.toString(Charsets.UTF_16)
            else -> bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF")
        }
        require(!text.contains("<!DOCTYPE", true) && !text.contains("<!ENTITY", true)) { "Entity declarations forbidden" }
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        }
        val builder = factory.newDocumentBuilder()
        builder.setEntityResolver { _, _ -> throw IllegalArgumentException("External entities forbidden") }
        return builder.parse(org.xml.sax.InputSource(java.io.StringReader(text)))
    }
    fun entries(input: InputStream): Map<String, ByteArray> {
        val result = linkedMapOf<String, ByteArray>(); var total = 0; var count = 0
        ZipInputStream(input).use { zip -> while (true) {
            val entry = zip.nextEntry ?: break
            require(++count <= 1024) { "Too many Office parts" }
            require(!entry.name.startsWith('/') && entry.name.split('/').none { it == ".." }) { "Unsafe Office entry" }
            val bytes = zip.readNBytesCompat(4 * 1024 * 1024 + 1)
            total += bytes.size
            require(bytes.size <= 4 * 1024 * 1024 && total <= 16 * 1024 * 1024) { "Office document too large" }
            require(!result.containsKey(entry.name)) { "Duplicate Office part" }
            result[entry.name] = bytes
        } }
        return result
    }
    fun texts(doc: Document, tag: String): List<String> {
        val nodes = doc.getElementsByTagNameNS("*", tag)
        return (0 until nodes.length).map { nodes.item(it).textContent }
    }
}

private const val OFFICE_REL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"

/** Resolves package relationships as URI paths, without opening any target. */
private fun relationshipTargets(parts: Map<String, ByteArray>, owner: String): Map<String, String> {
    val directory = owner.substringBeforeLast('/', "")
    val name = owner.substringAfterLast('/')
    val relsPath = "$directory/_rels/$name.rels"
    val bytes = parts[relsPath] ?: return emptyMap()
    val nodes = OfficeXml.parse(bytes).getElementsByTagNameNS("*", "Relationship")
    val targets = linkedMapOf<String, String>()
    for (index in 0 until nodes.length) {
        val element = nodes.item(index) as org.w3c.dom.Element
        if (element.getAttribute("TargetMode").equals("External", true)) continue
        val target = element.getAttribute("Target")
        val uri = runCatching { java.net.URI(owner).resolve(target).normalize() }.getOrNull() ?: continue
        if (uri.isAbsolute || uri.rawAuthority != null || uri.rawQuery != null || uri.rawFragment != null) continue
        val path = uri.path.removePrefix("/")
        if (path.split('/').any { it == ".." } || !parts.containsKey(path)) continue
        targets[element.getAttribute("Id")] = path
    }
    return targets
}

/** Preview intentionally bounds rows, columns, cells and text before reaching the Android UI. */
data class SpreadsheetPreview(val name: String, val rows: List<List<String>>, val truncated: Boolean = false)

object XlsxTextExtractor {
    fun preview(input: InputStream): List<SpreadsheetPreview> = previewParts(OfficeXml.entries(input))

    private fun previewParts(parts: Map<String, ByteArray>): List<SpreadsheetPreview> {
        val shared = parts["xl/sharedStrings.xml"]?.let { OfficeXml.texts(OfficeXml.parse(it), "si") }.orEmpty()
        val workbook = parts["xl/workbook.xml"]
        val sheetPaths: List<Pair<String, String>> = if (workbook != null) {
            val relationships = relationshipTargets(parts, "xl/workbook.xml")
            val sheets = OfficeXml.parse(workbook).getElementsByTagNameNS("*", "sheet")
            (0 until minOf(sheets.length, 50)).map { index ->
                val sheet = sheets.item(index) as org.w3c.dom.Element
                val id = sheet.getAttributeNS(OFFICE_REL, "id")
                val path = requireNotNull(relationships[id]) { "Worksheet relationship missing" }
                sheet.getAttribute("name").take(200) to path
            }
        } else {
            parts.keys.filter { it.matches(Regex("xl/worksheets/sheet[0-9]+\\.xml")) }
                .sortedBy { it.substringAfterLast("sheet").substringBefore('.').toIntOrNull() ?: Int.MAX_VALUE }
                .take(50).mapIndexed { index, path -> "Sheet ${index + 1}" to path }
        }
        require(sheetPaths.isNotEmpty()) { "XLSX has no worksheets" }
        var remainingChars = PlainTextAttachment.MAX_CHARS
        var remainingCells = 100_000
        return sheetPaths.map { (name, path) ->
            val rowNodes = OfficeXml.parse(parts.getValue(path)).getElementsByTagNameNS("*", "row")
            val rows = mutableListOf<List<String>>()
            var clipped = false
            for (index in 0 until rowNodes.length) {
                if (rows.size >= 500 || remainingChars <= 0 || remainingCells <= 0) break
                val row = rowNodes.item(index) as org.w3c.dom.Element
                val requestedRow = row.getAttribute("r").toIntOrNull()?.minus(1) ?: rows.size
                if (requestedRow !in rows.size..499) { clipped = true; continue }
                while (rows.size < requestedRow) rows.add(emptyList())
                val values = mutableListOf<String>()
                val cells = row.getElementsByTagNameNS("*", "c")
                for (cellIndex in 0 until cells.length) {
                    if (remainingChars <= 0 || remainingCells <= 0) break
                    val cell = cells.item(cellIndex) as org.w3c.dom.Element
                    val reference = cell.getAttribute("r")
                    val column = if (reference.isBlank()) values.size else columnIndex(reference)
                    if (column !in values.size..255) { clipped = true; continue }
                    while (values.size < column) values.add("")
                    val value = cell.getElementsByTagNameNS("*", "v").item(0)?.textContent.orEmpty()
                    val decoded = when (cell.getAttribute("t")) {
                        "s" -> shared.getOrElse(value.toIntOrNull() ?: -1) { "" }
                        "inlineStr" -> cell.getElementsByTagNameNS("*", "is").item(0)?.textContent.orEmpty()
                        "b" -> if (value == "1") "true" else "false"
                        else -> value
                    }
                    val capped = decoded.take(minOf(10_000, remainingChars))
                    if (capped.length < decoded.length) clipped = true
                    remainingChars -= capped.length
                    remainingCells--
                    values.add(capped)
                }
                rows.add(values)
            }
            SpreadsheetPreview(name, rows, clipped || rowNodes.length > rows.size || remainingChars <= 0 || remainingCells <= 0)
        }
    }

    private fun columnIndex(reference: String): Int {
        val letters = reference.takeWhile { it in 'A'..'Z' || it in 'a'..'z' }
        if (letters.isEmpty() || letters.length > 3) return -1
        var result = 0
        for (letter in letters) result = result * 26 + (letter.uppercaseChar() - 'A' + 1)
        return result - 1
    }

    fun extract(input: InputStream, maxChars: Int = PlainTextAttachment.MAX_CHARS): String {
        require(maxChars in 1..PlainTextAttachment.MAX_CHARS)
        val text = preview(input).joinToString("\n\n") { sheet ->
            sheet.name + "\n" + sheet.rows.joinToString("\n") { it.joinToString("\t") } +
                if (sheet.truncated) PlainTextAttachment.TRUNCATION_MARKER else ""
        }
        return PlainTextAttachment.cap(text, maxChars)
    }
}

object PptxTextExtractor {
    fun extract(input: InputStream, maxChars: Int = PlainTextAttachment.MAX_CHARS): String {
        require(maxChars in 1..PlainTextAttachment.MAX_CHARS)
        val parts = OfficeXml.entries(input)
        val presentation = parts["ppt/presentation.xml"]
        val slides = if (presentation != null) {
            val targets = relationshipTargets(parts, "ppt/presentation.xml")
            val ids = OfficeXml.parse(presentation).getElementsByTagNameNS("*", "sldId")
            (0 until ids.length).map { index ->
                val element = ids.item(index) as org.w3c.dom.Element
                requireNotNull(targets[element.getAttributeNS(OFFICE_REL, "id")]) { "Slide relationship missing" }
            }
        } else {
            parts.keys.filter { it.matches(Regex("ppt/slides/slide[0-9]+\\.xml")) }
                .sortedBy { it.substringAfterLast("slide").substringBefore('.').toIntOrNull() ?: Int.MAX_VALUE }
        }
        require(slides.isNotEmpty()) { "PPTX has no slides" }
        val text = StringBuilder()
        slides.forEachIndexed { index, path ->
            if (text.length <= maxChars) {
                val slide = OfficeXml.texts(OfficeXml.parse(parts.getValue(path)), "t").joinToString("\n")
                val targets = relationshipTargets(parts, path)
                val notesPath = targets.values.firstOrNull { it.startsWith("ppt/notesSlides/") }
                    ?: "ppt/notesSlides/notesSlide${path.substringAfterLast("slide").substringBefore('.')}.xml"
                val notes = parts[notesPath]?.let { OfficeXml.texts(OfficeXml.parse(it), "t").joinToString("\n") }.orEmpty()
                text.append("Slide ${index + 1}\n").append(slide.take(maxChars + 1))
                if (notes.isNotEmpty()) text.append("\nNotes: ").append(notes.take(maxChars + 1))
                text.append("\n\n")
            }
        }
        return PlainTextAttachment.cap(text.toString().trimEnd(), maxChars)
    }
}
