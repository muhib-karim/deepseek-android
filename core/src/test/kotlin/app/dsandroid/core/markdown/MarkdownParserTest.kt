package app.dsandroid.core.markdown

import org.junit.Assert.*
import org.junit.Test

class MarkdownParserTest {
    @Test fun numberedCitationBeforeMarkdownLinkStaysPlainText() {
        val spans = MarkdownParser.inline("Evidence [1]. See [docs](https://example.org)")
        assertEquals("Evidence [1]. See docs", spans.joinToString("") { it.text })
        assertTrue(spans.filter { it.link == null }.joinToString("") { it.text }.contains("[1]"))
        assertEquals("docs", spans.single { it.link != null }.text)
    }
    @Test fun parsesBlocksIncludingListsQuotesRulesAndFences() {
        val blocks = MarkdownParser.parse("# Title\n\nOne\nparagraph\n\n- alpha\n- beta\n\n3. three\n4. four\n\n> quoted\n> again\n\n---\n\n```kotlin\nval n = 1\n```")
        assertEquals(MarkdownBlock.Heading(1, "Title"), blocks[0])
        assertEquals(MarkdownBlock.Paragraph("One\nparagraph"), blocks[1])
        assertEquals(MarkdownBlock.ListBlock(false, listOf("alpha", "beta")), blocks[2])
        assertEquals(MarkdownBlock.ListBlock(true, listOf("three", "four"), 3), blocks[3])
        assertEquals(MarkdownBlock.Quote("quoted\nagain"), blocks[4])
        assertEquals(MarkdownBlock.Rule, blocks[5])
        assertEquals(MarkdownBlock.Code("val n = 1", "kotlin"), blocks[6])
    }
    @Test fun tableRequiresAlignmentRowAndPreservesEscapedPipes() {
        val table = MarkdownParser.parse("| A | B | C |\n| :--- | :---: | ---: |\n| x\\|y | **bold** | 3 |\n| short | row |").single() as MarkdownBlock.Table
        assertEquals(listOf(TableAlignment.LEFT, TableAlignment.CENTER, TableAlignment.RIGHT), table.alignments)
        assertEquals(listOf("x|y", "**bold**", "3"), table.rows[0])
        assertEquals(listOf("short", "row", ""), table.rows[1])
        assertTrue(MarkdownParser.parse("A | B\nnot a delimiter").single() is MarkdownBlock.Paragraph)
    }
    @Test fun inlineStylesLinksAndMathHaveNoFormattingDelimiters() {
        val spans = MarkdownParser.inline("**bold** *italic* `code` [site](https://example.com) ~~gone~~ ${'$'}x+1${'$'}")
        assertEquals("bold italic code site gone x+1", spans.joinToString("") { it.text })
        assertTrue(spans.first().bold)
        assertTrue(spans.any { it.italic && it.text == "italic" })
        assertTrue(spans.any { it.code && it.text == "code" })
        assertTrue(spans.any { it.link == "https://example.com" })
        assertTrue(spans.any { it.strikethrough })
        assertTrue(spans.last().code)
        assertTrue(MarkdownParser.inline("**bold and *nested***").any { it.bold && it.italic })
    }
    @Test fun underscoresInsideAWordAreLiteral() {
        val spans = MarkdownParser.inline("Noted: data_analysis__describe_csv was not run. __bold__")
        assertEquals("Noted: data_analysis__describe_csv was not run. bold", spans.joinToString("") { it.text })
        assertTrue(spans.single { it.bold }.text == "bold")
    }

    @Test fun incompleteStreamingMarkupRendersWithoutLeakingMarkers() {
        assertEquals(listOf(MarkdownBlock.Code("partial", "kt")), MarkdownParser.parse("```kt\npartial"))
        assertEquals("unfinished", MarkdownParser.inline("**unfinished").joinToString("") { it.text })
        assertEquals("partial", MarkdownParser.inline("`partial").joinToString("") { it.text })
        assertEquals(emptyList<MarkdownBlock>(), MarkdownParser.parse(""))
        assertEquals(listOf(MarkdownBlock.Code("", "")), MarkdownParser.parse("```"))
    }
}
