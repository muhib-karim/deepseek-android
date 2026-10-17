package app.dsandroid.core.tools

import app.dsandroid.core.attachments.*
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Test

class OfficeLimitsTest {
    private fun archive(parts:Map<String,String>):ByteArray {
        val out=ByteArrayOutputStream()
        ZipOutputStream(out).use { zip -> parts.forEach { (path,text) -> zip.putNextEntry(ZipEntry(path)); zip.write(text.toByteArray()); zip.closeEntry() } }
        return out.toByteArray()
    }
    @Test fun xlsxReadsSharedStringsAndEnforcesTextCap() {
        val bytes=archive(mapOf("xl/sharedStrings.xml" to "<sst><si><t>Shared value</t></si></sst>","xl/worksheets/sheet1.xml" to "<worksheet><sheetData><row><c t='s'><v>0</v></c><c><v>42</v></c></row></sheetData></worksheet>"))
        assertTrue(XlsxTextExtractor.extract(bytes.inputStream()).contains("Shared value\t42"))
        assertTrue(XlsxTextExtractor.extract(bytes.inputStream(),4).endsWith(PlainTextAttachment.TRUNCATION_MARKER))
    }
    @Test fun officeReadersRejectMissingPartsAndTraversal() {
        val empty=archive(mapOf("irrelevant.xml" to "<x/>"))
        assertThrows(IllegalArgumentException::class.java) { XlsxTextExtractor.extract(empty.inputStream()) }
        assertThrows(IllegalArgumentException::class.java) { PptxTextExtractor.extract(empty.inputStream()) }
        assertThrows(IllegalArgumentException::class.java) { OfficeXml.entries(archive(mapOf("../unsafe.xml" to "<x/>" )).inputStream()) }
    }
    @Test fun searchFiltersPrivateDestinationsAndLimitsResults() {
        val privateResult="<a class='result__a' href='https://127.0.0.1/'>private</a>"
        val entries=(1..12).joinToString("") { "<a class='result__a' href='https://example.org/$it'>Result $it</a><a class='result__snippet'>Snippet</a>" }
        val results=WebSearchParser.parse(privateResult+entries)
        assertEquals(8,results.size); assertTrue(results.none { it.url.contains("127.0.0.1") })
    }
    @Test fun spreadsheetPreviewPreservesWorkbookOrderNamesAndSparseCells() {
        val bytes = archive(mapOf(
            "xl/workbook.xml" to "<workbook xmlns:r='http://schemas.openxmlformats.org/officeDocument/2006/relationships'><sheets><sheet name='First named' r:id='b'/><sheet name='Second named' r:id='a'/></sheets></workbook>",
            "xl/_rels/workbook.xml.rels" to "<Relationships><Relationship Id='a' Target='worksheets/sheet1.xml'/><Relationship Id='b' Target='worksheets/sheet2.xml'/></Relationships>",
            "xl/worksheets/sheet1.xml" to "<worksheet><sheetData><row><c t='inlineStr'><is><t>Second</t></is></c></row></sheetData></worksheet>",
            "xl/worksheets/sheet2.xml" to "<worksheet><sheetData><row r='2'><c r='B2' t='inlineStr'><is><t>Left</t></is></c><c r='D2'><v>12</v></c></row></sheetData></worksheet>"
        ))
        val preview = XlsxTextExtractor.preview(bytes.inputStream())
        assertEquals(listOf("First named", "Second named"), preview.map { it.name })
        assertEquals(emptyList<String>(), preview.first().rows[0])
        assertEquals(listOf("", "Left", "", "12"), preview.first().rows[1])
        val text = XlsxTextExtractor.extract(bytes.inputStream())
        assertTrue(text.indexOf("First named") < text.indexOf("Second named"))
    }
    @Test fun presentationUsesRelationshipOrderInsteadOfPartNumbers() {
        val bytes = archive(mapOf(
            "ppt/presentation.xml" to "<presentation xmlns:r='http://schemas.openxmlformats.org/officeDocument/2006/relationships'><sldIdLst><sldId r:id='second'/><sldId r:id='first'/></sldIdLst></presentation>",
            "ppt/_rels/presentation.xml.rels" to "<Relationships><Relationship Id='first' Target='slides/slide1.xml'/><Relationship Id='second' Target='slides/slide2.xml'/></Relationships>",
            "ppt/slides/slide1.xml" to "<slide><t>Later</t></slide>",
            "ppt/slides/slide2.xml" to "<slide><t>Earlier</t></slide>"
        ))
        val text = PptxTextExtractor.extract(bytes.inputStream())
        assertTrue(text.indexOf("Earlier") < text.indexOf("Later"))
    }
}
