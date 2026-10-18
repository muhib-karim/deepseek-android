package app.dsandroid.core.attachments

import org.junit.Assert.*
import org.junit.Test

class AttachmentBatchTest {
    @Test fun fiftyLargeFilesHaveBoundedContextAndEveryFileIsRepresented() {
        val files = (1..50).map { AttachmentContent("file-$it.txt", AttachmentKind.TEXT, "x".repeat(200_000)) }
        val text = AttachmentContextBuilder.message("Compare", files, false).content.orEmpty()
        assertTrue(text.length < 140_000)
        (1..50).forEach { assertTrue(text.contains("file-$it.txt")) }
        assertTrue(text.contains("truncated", ignoreCase = true))
    }
    @Test fun codeWithGenericMimeIsReadable() {
        listOf("app.kt", "main.py", "index.js", "view.tsx", "styles.css", "main.rs", "schema.sql").forEach {
            assertEquals(AttachmentKind.TEXT, AttachmentKind.detect("application/octet-stream", it))
        }
    }
    @Test fun excessiveCountRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            AttachmentContextBuilder.message("", List(51) { AttachmentContent("x", AttachmentKind.TEXT) }, false)
        }
    }
}
