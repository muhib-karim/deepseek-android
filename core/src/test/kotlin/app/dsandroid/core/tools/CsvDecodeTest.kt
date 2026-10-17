package app.dsandroid.core.tools

import org.junit.Assert.*
import org.junit.Test

class CsvDecodeTest {
    @Test fun readsQuotedCellsNewlinesAndTrailingEmptyCells() {
        assertEquals(listOf(listOf("Name", "Note", ""), listOf("a,b", "two\nlines and \"quotes\"", "")),
            Csv.decode("Name,Note,\r\n\"a,b\",\"two\nlines and \"\"quotes\"\"\",\r\n"))
    }
}
