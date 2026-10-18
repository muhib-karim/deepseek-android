package app.dsandroid.core.pdf

import app.dsandroid.core.document.DocBlock
import app.dsandroid.core.document.DocumentModel
import org.junit.Assert.*
import org.junit.Test

class PdfLayoutEngineTest {
    private val spec = PdfPageSpec()
    private val engine = PdfLayoutEngine(ApproximateMeasurer, spec)
    private val right = spec.margin + spec.contentWidth + 0.01f

    private fun texts(page: PdfPage) = page.ops.filterIsInstance<PdfOp.Text>()
    private fun bodyTexts(layout: PdfLayout) = layout.pages.flatMap { page -> texts(page).filterNot { it.text.matches(Regex("\\d+ / \\d+")) } }
    private fun PdfOp.Text.end() = x + ApproximateMeasurer.width(text, font, size)

    @Test fun documentModelKeepsNestingNumberingAndPageBreaks() {
        val blocks = DocumentModel.parse("# Title\n\n1. one\n   - child\n   - child two\n2. two\n\n\\pagebreak\n\n```\n\\pagebreak\n```\n\n- a\n1. restart")
        assertEquals(DocBlock.Heading::class, blocks[0]::class)
        val items = blocks.filterIsInstance<DocBlock.ListItem>()
        assertEquals(listOf(0, 1, 1, 0, 0, 0), items.map { it.depth })
        assertEquals(1, items[0].number)
        assertEquals(2, items[3].number)
        assertFalse(items[1].ordered)
        assertEquals(1, items[5].number)
        assertEquals(1, blocks.count { it == DocBlock.PageBreak })
        assertEquals("\\pagebreak", (blocks.single { it is DocBlock.Code } as DocBlock.Code).text)
    }

    @Test fun longDocumentsPaginateInOrderWithinMargins() {
        val words = (1..2500).map { "word$it" }
        val markdown = words.chunked(25).joinToString("\n\n") { it.joinToString(" ") }
        val layout = engine.layout("Pagination", markdown)
        assertTrue(layout.pageCount > 3)
        assertEquals(layout.pageCount, layout.pages.last().number)
        val flowed = bodyTexts(layout).drop(1).joinToString(" ") { it.text }.split(' ').filter { it.isNotBlank() }
        assertEquals(words, flowed)
        layout.pages.forEach { page ->
            texts(page).forEach { text ->
                assertTrue(text.x >= spec.margin - 0.01f)
                assertTrue(text.end() <= right)
                assertTrue(text.baseline <= spec.height - spec.margin / 2 + 0.01f)
            }
        }
        assertEquals("1 / ${layout.pageCount}", texts(layout.pages.first()).last().text)
    }

    @Test fun wrapsParagraphsAndBreaksWordsLongerThanALine() {
        val url = "https://example.com/" + "a".repeat(600)
        val layout = engine.layout(null, "Intro text before a very long link $url and after.")
        val lines = bodyTexts(layout)
        assertTrue(lines.size >= 6)
        lines.forEach { assertTrue("Line exceeds the content width", it.end() <= right) }
        val joined = lines.joinToString("") { it.text }
        assertTrue(joined.replace(" ", "").contains(url))
    }

    @Test fun boldItalicAndCodeRunsUseTheirFonts() {
        val layout = engine.layout(null, "Plain **bold** *italic* `code` text")
        val fonts = bodyTexts(layout).associate { it.text.trim() to it.font }
        assertEquals(PdfFont.BOLD, fonts["bold"])
        assertEquals(PdfFont.ITALIC, fonts["italic"])
        assertEquals(PdfFont.MONO, fonts["code"])
    }

    @Test fun headingsListsQuotesAndRulesRender() {
        val layout = engine.layout(null, "## Section\n\n- first\n  - nested\n\n1. one\n2. two\n\n> quoted\n\n---\n\nEnd")
        val texts = bodyTexts(layout)
        assertEquals(PdfFont.BOLD, texts.first { it.text == "Section" }.font)
        val bullet = texts.first { it.text == "\u2022" }
        val nestedBullet = texts.first { it.text == "\u25E6" }
        assertTrue(nestedBullet.x > bullet.x)
        assertTrue(texts.first { it.text == "nested" }.x > texts.first { it.text == "first" }.x)
        assertTrue(texts.any { it.text == "1." } && texts.any { it.text == "2." })
        assertEquals(PdfFont.ITALIC, texts.first { it.text == "quoted" }.font)
        assertTrue(layout.pages.first().ops.filterIsInstance<PdfOp.Line>().size >= 2)
    }

    @Test fun codeBlocksKeepLinesInMonospaceOnAShadedBox() {
        val code = (1..120).joinToString("\n") { "line_$it = $it  # " + "x".repeat(it % 7) }
        val layout = engine.layout(null, "```python\n$code\n```")
        assertTrue(layout.pageCount >= 2)
        val monospace = bodyTexts(layout).filter { it.font == PdfFont.MONO }
        assertEquals(120, monospace.size)
        assertEquals("line_1 = 1  # x", monospace.first().text)
        layout.pages.forEach { page ->
            val boxes = page.ops.filterIsInstance<PdfOp.Box>().filter { it.fill == PdfLayoutEngine.CODE_FILL }
            assertEquals(1, boxes.size)
            assertTrue("Shading must be drawn before its text", page.ops.indexOf(boxes.single()) < page.ops.indexOfFirst { it is PdfOp.Text })
        }
    }

    @Test fun tablesDrawBordersFillHeadersAndWrapCells() {
        val long = (1..60).joinToString(" ") { "detail$it" }
        val layout = engine.layout(null, "| Name | Notes | Qty |\n|---|---|---:|\n| Alpha | $long | 3 |\n| Beta | short | 12 |")
        val boxes = layout.pages.flatMap { it.ops.filterIsInstance<PdfOp.Box>() }
        assertEquals(9, boxes.size)
        assertEquals(3, boxes.count { it.fill == PdfLayoutEngine.HEADER_FILL })
        assertTrue(boxes.all { it.stroke == PdfLayoutEngine.RULE })
        val rowWidth = boxes.take(3).sumOf { it.width.toDouble() }
        assertEquals(spec.contentWidth.toDouble(), rowWidth, 0.5)
        assertTrue("Narrow columns keep their natural width", boxes[0].width < boxes[1].width && boxes[2].width < boxes[1].width)
        val notes = boxes[4]
        val noteLines = bodyTexts(layout).filter { it.text.contains("detail") }
        assertTrue(noteLines.size > 2)
        noteLines.forEach { assertTrue(it.x >= notes.x && it.end() <= notes.x + notes.width + 0.01f) }
        val qty = bodyTexts(layout).first { it.text == "12" }
        val qtyBox = boxes[8]
        assertEquals("Right-aligned column", qtyBox.x + qtyBox.width - 4f, qty.end(), 0.5f)
    }

    @Test fun tablesSpanningPagesRepeatTheHeader() {
        val rows = (1..150).joinToString("\n") { "| r$it | value $it |" }
        val layout = engine.layout(null, "| Key | Value |\n|---|---|\n$rows")
        assertTrue(layout.pageCount >= 2)
        layout.pages.forEach { page ->
            assertTrue("Header on page ${page.number}", texts(page).any { it.text == "Key" && it.font == PdfFont.BOLD })
        }
        assertEquals((1..150).map { "r$it" }, bodyTexts(layout).filter { it.text.matches(Regex("r\\d+")) }.map { it.text })
    }

    @Test fun rowsTallerThanAPageAreSplitAcrossPages() {
        val huge = (1..1500).joinToString(" ") { "w$it" }
        val layout = engine.layout(null, "| A | B |\n|---|---|\n| $huge | b |")
        assertTrue(layout.pageCount >= 2)
        val flowed = bodyTexts(layout).filter { it.text.startsWith("w") }.joinToString(" ") { it.text }.split(' ').filter { it.isNotBlank() }
        assertEquals((1..1500).map { "w$it" }, flowed)
    }

    @Test fun explicitPageBreaksStartNewPagesAndThePageLimitHolds() {
        val layout = engine.layout(null, "Page one\n\n<!-- pagebreak -->\n\nPage two\n\n\\newpage\n\nPage three")
        assertEquals(3, layout.pageCount)
        assertEquals("Page two", bodyTexts(PdfLayout(layout.width, layout.height, listOf(layout.pages[1]))).joinToString(" ") { it.text })
        val tooMany = (1..PdfLayoutEngine.MAX_PAGES + 1).joinToString("\n\n\\pagebreak\n\n") { "p$it" }
        assertThrows(IllegalArgumentException::class.java) { engine.layout(null, tooMany) }
    }

    @Test fun emptyInputStillProducesOnePage() {
        assertEquals(1, engine.layout(null, "").pageCount)
        assertEquals(1, engine.layout("Only a title", "").pageCount)
    }
}
