package app.dsandroid.core.attachments

import org.junit.Assert.*
import org.junit.Test
class OfficeAttachmentsTest {
    @Test fun officeTypesAreRecognizedAndFileCountIsBounded() {
        assertEquals(AttachmentKind.XLSX,AttachmentKind.detect(null,"table.xlsx"))
        assertEquals(AttachmentKind.PPTX,AttachmentKind.detect(null,"deck.pptx"))
        val file=AttachmentContent("note.txt",AttachmentKind.TEXT,"text")
        assertTrue(AttachmentContextBuilder.message("",List(50) { file },false).content.orEmpty().contains("text"))
        assertThrows(IllegalArgumentException::class.java) { AttachmentContextBuilder.message("",List(51) { file },false) }
    }
}
