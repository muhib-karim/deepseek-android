package app.dsandroid.core.attachments

import org.junit.Assert.*
import org.junit.Test

class SharedContentTest {
    @Test fun rejectsPrivateAndNonContentUrisAndDeduplicatesBeforeCapping() {
        val accepted = SharedContentPolicy.uris(listOf("file:///private", "https://example.org/a", "content://app.dsandroid.artifacts/workspace/a", "content://app%2edsandroid.artifacts/workspace/a", "content://media/1", "content://media/1"), "app.dsandroid.artifacts")
        assertEquals(listOf("content://media/1"), accepted)
        assertEquals(50, SharedContentPolicy.uris((1..60).map { "content://media/$it" }, "private").size)
    }
}
