package app.dsandroid.core.tools

import app.dsandroid.core.artifacts.CsvPreview
import org.junit.Assert.*
import org.junit.Test

class CsvPreviewTest {
    @Test fun quotedCellsRemainSeparateAndMarkupNeverBecomesActive() {
        val html = CsvPreview.render("Name,Value\r\n\"one,two\",\"<script>\"\r\n")
        assertTrue(html.contains("<th>Name</th><th>Value</th>"))
        assertTrue(html.contains("<td>one,two</td><td>&lt;script&gt;</td>"))
        assertFalse(html.contains("<script>"))
    }

    @Test fun excessiveTablesFailExplicitlyInsteadOfSilentlyLosingRows() {
        assertThrows(IllegalArgumentException::class.java) {
            CsvPreview.render((1..501).joinToString("\n") { "a,b" })
        }
        assertEquals("<table></table>", CsvPreview.render(""))
    }
}
