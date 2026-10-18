package app.dsandroid.core.document

import app.dsandroid.core.attachments.DocxTextExtractor
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.*
import org.junit.Test
import org.w3c.dom.Document

class DocumentWritersTest {
    private val wordNs = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"

    private fun unzip(bytes: ByteArray): Map<String, String> {
        val entries = linkedMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
            }
        }
        return entries
    }

    private fun parse(xml: String): Document = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        .newDocumentBuilder().parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))

    private val markdown = """
        # Quarterly report

        Revenue grew **12%** with *steady* margins & <care>.

        - North
          - Coastal
        - South

        1. Plan
        2. Execute

        ```kotlin
        val total = 42
        ```

        | Region | Revenue |
        |---|---:|
        | North | 1,200 |
        | South | 980 |

        \pagebreak

        > Notes follow.
    """.trimIndent()

    @Test fun docxIsAMinimalValidPackageWithWellFormedParts() {
        val bytes = DocxWriter.build("Q3 Report", markdown)
        val parts = unzip(bytes)
        assertEquals("[Content_Types].xml", parts.keys.first())
        listOf("_rels/.rels", "docProps/core.xml", "word/_rels/document.xml.rels", "word/document.xml", "word/styles.xml", "word/numbering.xml")
            .forEach { assertTrue("Missing $it", it in parts) }
        parts.values.forEach { parse(it) }
        assertTrue(parts.getValue("[Content_Types].xml").contains("wordprocessingml.document.main+xml"))
        assertTrue(parts.getValue("_rels/.rels").contains("Target=\"word/document.xml\""))
    }

    @Test fun docxMapsMarkdownToWordStructures() {
        val parts = unzip(DocxWriter.build("Q3 Report", markdown))
        val document = parse(parts.getValue("word/document.xml"))
        fun count(tag: String) = document.getElementsByTagNameNS(wordNs, tag).length
        val styles = (0 until count("pStyle")).map { (document.getElementsByTagNameNS(wordNs, "pStyle").item(it) as org.w3c.dom.Element).getAttributeNS(wordNs, "val") }
        assertTrue(styles.containsAll(listOf("Title", "Heading1", "ListParagraph", "Code", "Quote")))
        assertEquals(5, count("numPr"))
        assertEquals(1, count("tbl"))
        assertEquals(3, count("tr"))
        assertEquals(1, count("tblHeader"))
        assertTrue(count("b") >= 1 && count("i") >= 1)
        val xml = parts.getValue("word/document.xml")
        assertTrue(xml.contains("<w:br w:type=\"page\"/>"))
        assertTrue(xml.contains("<w:ilvl w:val=\"1\"/>"))
        assertTrue(xml.contains("<w:jc w:val=\"right\"/>"))
        assertTrue(xml.contains("&amp; &lt;care&gt;"))
        val numbering = parts.getValue("word/numbering.xml")
        assertTrue(numbering.contains("<w:numFmt w:val=\"bullet\"/>") && numbering.contains("<w:numFmt w:val=\"decimal\"/>"))
        assertTrue(numbering.contains("<w:num w:numId=\"2\">"))
    }

    @Test fun docxTextRoundTripsThroughTheExtractor() {
        val text = DocxTextExtractor.extract(ByteArrayInputStream(DocxWriter.build("Q3 Report", markdown)))
        listOf("Q3 Report", "Quarterly report", "Revenue grew 12% with steady margins & <care>.", "Coastal", "val total = 42", "1,200", "Notes follow.")
            .forEach { assertTrue("Missing $it in $text", text.contains(it)) }
    }

    @Test fun separateOrderedListsRestartNumbering() {
        val numbering = unzip(DocxWriter.build(null, "1. a\n2. b\n\nBreak\n\n1. c")).getValue("word/numbering.xml")
        assertTrue(numbering.contains("<w:num w:numId=\"2\">") && numbering.contains("<w:num w:numId=\"3\">"))
        assertEquals(2, Regex("startOverride w:val=\"1\"").findAll(numbering).count())
    }

    @Test fun csvIsNormalizedToRfc4180() {
        assertEquals("name,note\r\nAda,\"hello, world\"\r\nBob,\"say \"\"hi\"\"\"\r\n",
            CsvWriter.normalize("name,note\nAda,\"hello, world\"\nBob,\"say \"\"hi\"\"\"\n"))
        assertEquals("a,b\r\n1,2\r\n", CsvWriter.normalize("\uFEFFa,b\r\n1,2"))
        assertEquals("ü,€\r\n", CsvWriter.normalize("ü,€"))
        assertThrows(IllegalArgumentException::class.java) { CsvWriter.normalize("a,\"unclosed\n1,2") }
        assertThrows(IllegalArgumentException::class.java) { CsvWriter.normalize("\n\n") }
    }

    @Test fun csvQuotesOnlyWhereNeeded() {
        assertEquals("plain,\"comma,cell\",\"line\nbreak\",\"quote\"\"d\"\r\n", CsvWriter.fromRows(listOf(listOf("plain", "comma,cell", "line\nbreak", "quote\"d"))))
    }
}
