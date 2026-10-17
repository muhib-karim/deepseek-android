package app.dsandroid.core.tools

import org.junit.Assert.*
import org.junit.Test
import javax.xml.parsers.DocumentBuilderFactory

class ChartAndWebTest {
    @Test fun allChartTypesProduceEscapedParseableSvg() {
        for (type in listOf("bar", "line", "pie")) {
            val svg = ChartSvgBuilder.build(type, "A & B", listOf("One", "Two"), listOf(ChartSeries("Values", listOf(2.0, 4.0))))
            DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(svg.byteInputStream())
            assertTrue(svg.contains("A &amp; B")); assertFalse(svg.contains("NaN"))
        }
        assertThrows(IllegalArgumentException::class.java) { ChartSvgBuilder.build("pie", "", listOf("x"), listOf(ChartSeries("a", listOf(-1.0)))) }
    }
    @Test fun parsesSearchFixtureAndReadablePage() {
        val fixture = "<a class='result__a' href='https://example.org/report'>Report &amp; data</a><a class='result__snippet'>A useful result</a>"
        val results = WebSearchParser.parse(fixture)
        assertEquals("Report & data", results.single().title); assertEquals("https://example.org/report", results.single().url)
        val text = ReadableWebPage.extract("<title>Example</title><script>bad()</script><h1>Heading</h1><p>Readable paragraph</p><a href='/next'>Next</a>", "https://example.org/")
        assertTrue(text.contains("Heading")); assertTrue(text.contains("Readable paragraph")); assertTrue(text.contains("https://example.org/next")); assertFalse(text.contains("bad()"))
    }
}
