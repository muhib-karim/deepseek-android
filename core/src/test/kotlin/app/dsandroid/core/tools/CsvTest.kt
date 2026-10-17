package app.dsandroid.core.tools

import org.junit.Assert.*
import org.junit.Test

class CsvTest {
    @Test fun rfc4180EscapingAndCrLfRows() {
        assertEquals("plain,\"a,b\",\"say \"\"hi\"\"\",\"line\r\nnext\"\r\n,,end,\r\n",
            Csv.encode(listOf(listOf("plain", "a,b", "say \"hi\"", "line\r\nnext"), listOf("", "", "end", ""))))
    }
    @Test fun emptyTableHasNoPhantomRow() { assertEquals("", Csv.encode(emptyList())) }
}
