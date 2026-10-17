package app.dsandroid.ui

import org.junit.Assert.*
import org.junit.Test

class MarkdownBlocksTest {
    @Test fun paragraphsCodeFencesAndUnclosedStreamingFence() {
        val blocks = MarkdownBlocks.parse("First paragraph\ncontinues\n\n```kotlin\nval x = 1\n```\n\nLast")
        assertEquals(listOf(MarkdownBlock.Paragraph("First paragraph\ncontinues"), MarkdownBlock.Code("val x = 1", "kotlin"), MarkdownBlock.Paragraph("Last")), blocks)
        assertEquals(listOf(MarkdownBlock.Code("partial", "")), MarkdownBlocks.parse("```\npartial"))
    }
    @Test fun boldAndInlineCodeKeepLiteralContent() {
        assertEquals(listOf(InlinePart("Hello "), InlinePart("bold", bold = true), InlinePart(" "), InlinePart("x < y", code = true)),
            MarkdownBlocks.inline("Hello **bold** `x < y`"))
        assertEquals(listOf(InlinePart("**unfinished")), MarkdownBlocks.inline("**unfinished"))
        assertEquals(emptyList<MarkdownBlock>(), MarkdownBlocks.parse(""))
    }
}
