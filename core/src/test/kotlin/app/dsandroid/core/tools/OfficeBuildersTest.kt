package app.dsandroid.core.tools

import app.dsandroid.core.attachments.*
import java.io.ByteArrayInputStream
import java.net.URI
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.*
import org.junit.Test

class OfficeBuildersTest {
    private fun parts(bytes: ByteArray): Map<String, ByteArray> {
        val result = linkedMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip -> while (true) { val e = zip.nextEntry ?: break; result[e.name] = zip.readBytes() } }
        return result
    }
    private fun validPackage(bytes: ByteArray): Map<String, ByteArray> {
        val entries = parts(bytes)
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        entries.filterKeys { it.endsWith(".xml") || it.endsWith(".rels") }.forEach { (name, value) ->
            val doc = factory.newDocumentBuilder().parse(ByteArrayInputStream(value))
            if (name == "[Content_Types].xml") {
                val overrides = doc.getElementsByTagName("Override")
                for (i in 0 until overrides.length) assertTrue(entries.containsKey(overrides.item(i).attributes.getNamedItem("PartName").nodeValue.removePrefix("/")))
            }
            if (name.endsWith(".rels")) {
                val rels = doc.getElementsByTagName("Relationship")
                val base = if (name == "_rels/.rels") "" else name.substringBeforeLast("/_rels/") + "/"
                for (i in 0 until rels.length) {
                    val target = rels.item(i).attributes.getNamedItem("Target").nodeValue
                    assertTrue("Missing $name target $target", entries.containsKey(URI(base).resolve(target).normalize().path))
                }
            }
        }
        return entries
    }
    @Test fun docxIncludesStructuredTextAndRoundTrips() {
        val bytes = DocxBuilder.build("A & B", listOf(DocxBlock(heading="Heading"), DocxBlock(paragraph="Paragraph"), DocxBlock(bullets=listOf("First", "Second")), DocxBlock(table=listOf(listOf("Cell", "2")))))
        validPackage(bytes)
        val text = DocxTextExtractor.extract(ByteArrayInputStream(bytes))
        listOf("A & B", "Heading", "Paragraph", "First", "Second", "Cell").forEach { assertTrue(text.contains(it)) }
    }
    @Test fun xlsxContainsNumbersAndInlineStringsAndExtracts() {
        val bytes = XlsxBuilder.build(listOf(Sheet("Budget", listOf(listOf("Label", "12.5"), listOf("A & B", "-2")))))
        val entries = validPackage(bytes)
        assertTrue(String(entries.getValue("xl/worksheets/sheet1.xml")).contains("<v>12.5</v>"))
        val text = XlsxTextExtractor.extract(ByteArrayInputStream(bytes))
        assertTrue(text.contains("A & B")); assertTrue(text.contains("12.5"))
    }
    @Test fun pptxIncludesWideSlidesAndNotesAndExtractsInOrder() {
        val bytes = PptxBuilder.build("Deck", listOf(Slide("Topic", listOf("Point"), "Speaker notes")))
        val entries = validPackage(bytes)
        assertTrue(String(entries.getValue("ppt/presentation.xml")).contains("12192000"))
        assertTrue(entries.containsKey("ppt/notesMasters/notesMaster1.xml"))
        val text = PptxTextExtractor.extract(ByteArrayInputStream(bytes))
        assertTrue(text.indexOf("Deck") < text.indexOf("Topic")); assertTrue(text.contains("Point")); assertTrue(text.contains("Speaker notes"))
    }
    @Test fun extractionRejectsEntityDeclarations() {
        assertThrows(IllegalArgumentException::class.java) { OfficeXml.parse("<!DOCTYPE x [<!ENTITY x SYSTEM 'file:blocked'>]><x>&x;</x>".toByteArray()) }
    }
}
