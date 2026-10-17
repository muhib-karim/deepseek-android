package app.dsandroid.core.tools

import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.*
import org.junit.Test

class ToolRegressionTest {
    @Test fun missingSnippetDoesNotBorrowNextResultAndDuplicatesDoNotReduceLimit() {
        val first="<a class='result__a' href='https://example.org/one'>One</a>"
        val others=(2..10).joinToString("") { "<a class='result__a' href='https://example.org/$it'>Title</a><a class='result__snippet'>Snippet $it</a>" }
        val results=WebSearchParser.parse(first+first+others)
        assertEquals("",results.first().snippet)
        assertEquals(8,results.size)
        assertEquals("https://example.org/8",results.last().url)
    }
    @Test fun searxBaseUsesSearchPathAndReplacesExistingQuery() {
        assertEquals("https://example.org/search?q=new",WebSearchTool("https://example.org/?q=old").searchUrl("new"))
        assertEquals("https://example.org/custom/search?q=new",WebSearchTool("https://example.org/custom/search?q=old").searchUrl("new"))
    }
    @Test fun loneLinePointIsVisibleAndPieLegendIsBounded() {
        val svg=ChartSvgBuilder.build("line","Title",listOf("Label"),listOf(ChartSeries("Data",listOf(1.0))))
        assertTrue(svg.contains("<circle"))
        assertThrows(IllegalArgumentException::class.java) { ChartSvgBuilder.build("pie","",List(21) { "x" },listOf(ChartSeries("",List(21) { 1.0 }))) }
        val long=ChartSvgBuilder.build("bar","T".repeat(1000),listOf("L".repeat(1000)),listOf(ChartSeries("S".repeat(1000),listOf(1.0))))
        assertFalse(long.contains("L".repeat(100))); assertFalse(long.contains("S".repeat(100)))
    }
    @Test fun xmlFiltersInvalidCodepointsButPreservesSupplementaryCharacters() {
        val value="A\uFFFE\uFFFF\uD800B\uDC00😀"
        val escaped=xml(value)
        assertEquals("AB😀",escaped)
        DocumentBuilderFactory.newInstance().newDocumentBuilder().parse("<root>$escaped</root>".byteInputStream())
    }
    @Test fun buildersRejectOversizedInputsBeforeConstructingXml() {
        val huge="x".repeat(200001)
        assertThrows(IllegalArgumentException::class.java) { DocxBuilder.build("",listOf(DocxBlock(paragraph=huge))) }
        assertThrows(IllegalArgumentException::class.java) { XlsxBuilder.build(listOf(Sheet("A",listOf(listOf(huge))))) }
        assertThrows(IllegalArgumentException::class.java) { PptxBuilder.build("",listOf(Slide("",listOf(huge)))) }
    }
}
