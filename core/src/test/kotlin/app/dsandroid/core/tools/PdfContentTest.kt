package app.dsandroid.core.tools

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class PdfContentTest {
    @Test fun structuredContentKeepsTableCellsAndHeadingStyles() {
        val rows = PdfContent.rows(Json.parseToJsonElement("""{"title":"Report","blocks":[{"heading":"Results"},{"paragraph":"বাংলা"},{"table":[["A","B"],["1","2"]]}]}""").jsonObject)
        assertEquals(listOf("Report"), rows[0].cells)
        assertTrue(rows[0].heading)
        assertTrue(rows[1].heading)
        assertEquals("বাংলা", rows[2].cells.single())
        assertEquals(listOf("1", "2"), rows.last().cells)
        assertTrue(rows.last().table)
    }
    @Test fun legacyExportInputStillWorks() {
        assertEquals("Export\nbody", PdfContent.rows(buildJsonObject { put("content", "Export\nbody") }).single().cells.single())
    }
    @Test fun rejectsAmbiguousBlocksRaggedTablesAndOversizedInput() {
        listOf(
            """{"title":"T","blocks":[{"heading":"H","paragraph":"P"}]}""",
            """{"title":"T","blocks":[{"table":[["A","B"],["C"]]}]}""",
        ).forEach { json ->
            assertThrows(IllegalArgumentException::class.java) { PdfContent.rows(Json.parseToJsonElement(json).jsonObject) }
        }
        assertThrows(IllegalArgumentException::class.java) { PdfContent.rows(buildJsonObject { put("content", "x".repeat(100_001)) }) }
    }
}
