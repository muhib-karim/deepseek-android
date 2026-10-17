package app.dsandroid.core.tools

import app.dsandroid.core.artifacts.MarkdownPreview
import org.junit.Assert.*
import org.junit.Test

class MarkdownPreviewTest {
    @Test fun rendersTablesAndEscapesHtmlAndCodeWithoutFetchingLinks() {
        val html = MarkdownPreview.render("# Hello\n\n| A | B |\n| --- | --- |\n| 1 | 2 |\n\n<script>alert(1)</script>\n\n[Link](https://example.com)")
        assertTrue(html.contains("<h1>Hello</h1>"))
        assertTrue(html.contains("<table>"))
        assertTrue(html.contains("&lt;script&gt;"))
        assertFalse(html.contains("<script>"))
        assertFalse(html.contains("href="))
    }
}
