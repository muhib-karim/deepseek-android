package app.dsandroid.core.tools

import app.dsandroid.core.artifacts.*

import org.junit.Assert.*
import org.junit.Test

class FencedArtifactsTest {
    @Test fun extractsCompletedSupportedFencesWithoutNestedOrPartialBlocks() {
        val source = """
            Before
            ```html
            <h1>Hello</h1>
            ```
            ```kotlin
            ```html
            ```
            ~~~mermaid
            flowchart TD
            A --> B
            ~~~
            ```svg
            <svg/>
        """.trimIndent()
        val fences = FencedArtifacts.parse(source)
        assertEquals(listOf("html", "mermaid"), fences.map { it.format })
        assertEquals("<h1>Hello</h1>", fences.first().content)
    }

    @Test fun aliasesNormalizeAndInputIsBounded() {
        assertEquals("markdown", FencedArtifacts.parse("```md\n# Title\n```").single().format)
        assertEquals("txt", FencedArtifacts.parse("```text\nhello\n```").single().format)
        assertTrue(FencedArtifacts.parse("```html\n" + "x".repeat(2 * 1024 * 1024 + 1) + "\n```").isEmpty())
        assertEquals(8, FencedArtifacts.parse((1..12).joinToString("\n") { "```txt\n$it\n```" }).size)
    }
}
