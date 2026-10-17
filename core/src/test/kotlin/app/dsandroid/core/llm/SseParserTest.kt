package app.dsandroid.core.llm

import org.junit.Assert.*
import org.junit.Test

class SseParserTest {
    @Test fun splitChunksCommentsAndMultipleEvents() {
        val parser = SseParser()
        assertEquals(emptyList<String>(), parser.feed(": keepalive\r\nda"))
        assertEquals(listOf("one\ntwo", "[DONE]"), parser.feed("ta: one\r\ndata: two\r\n\r\ndata: [DONE]\n\n"))
    }
    @Test fun splitCrLfAndFinalUnterminatedEvent() {
        val parser = SseParser()
        assertEquals(emptyList<String>(), parser.feed("data: a\r"))
        assertEquals(listOf("a"), parser.feed("\n\r"))
        assertEquals(emptyList<String>(), parser.feed("\ndata: b"))
        assertEquals(listOf("b"), parser.finish())
    }
    @Test fun ignoresEventsWithoutData() {
        assertEquals(emptyList<String>(), SseParser().feed("event: ping\nid: 1\n\n"))
    }
    @Test fun limitsUnterminatedInput() {
        assertThrows(IllegalArgumentException::class.java) { SseParser(16).feed("data: " + "x".repeat(20)) }
    }
}
