package app.dsandroid.core.deepseek

import org.junit.Assert.*
import org.junit.Test

/**
 * Replays the three streams recorded on 2026-10-18 from the live web service in account mode
 * (test account, the app's own request contract). See docs/LIVE_VERIFY_051.md.
 */
class LiveRecordedStreamTest {
    private fun replay(name: String): WebReply {
        val wire = requireNotNull(javaClass.getResource("/live-2026-10-18/$name")) { "missing $name" }.readText()
        val stream = DeepSeekStream()
        stream.feed(wire)
        return stream.finish()
    }

    @Test fun basicChatReturnsPong7() {
        val reply = replay("chat_basic.sse")
        assertEquals("pong 7", reply.response)
        assertEquals("", reply.thinking)
        assertEquals(1L, reply.requestMessageId)
        assertEquals(2L, reply.responseMessageId)
        assertTrue(reply.finished)
    }

    @Test fun followUpWithDeepThinkHasThinkingAndAnswer7() {
        val reply = replay("chat_followup_deepthink.sse")
        assertTrue("thinking text should be non-empty", reply.thinking.isNotBlank())
        assertEquals("7", reply.response)
        assertEquals(3L, reply.requestMessageId)
        assertEquals(4L, reply.responseMessageId)
        assertTrue(reply.finished)
    }

    @Test fun searchReplyHandlesSearchFragment() {
        val reply = replay("chat_search.sse")
        assertTrue(reply.response.startsWith("The"))
        assertFalse("search status text must not leak into the answer", reply.response.contains("Found 12 web pages"))
        assertEquals("", reply.thinking)
        assertTrue(reply.finished)
    }

    @Test fun deepThinkReportsItsThinkingTime() {
        val reply = replay("chat_followup_deepthink.sse")
        assertEquals(0.809300272, reply.thinkingSeconds!!, 1e-9)
        assertNull("A reply without thinking has no thinking time", replay("chat_basic.sse").thinkingSeconds)
    }

    @Test fun searchKeepsThePagesItReadAsHttpsCitations() {
        val reply = replay("chat_search.sse")
        assertTrue("The recorded Search turn read several pages", reply.citations.size >= 2)
        assertTrue(reply.citations.size <= DeepSeekStream.MAX_CITATIONS)
        assertEquals("Android Studio Rabbit 1 now available", reply.citations.first().title)
        assertTrue(reply.citations.all { it.url.startsWith("https://") })
        assertEquals(reply.citations.size, reply.citations.map { it.url }.distinct().size)
        assertTrue(replay("chat_basic.sse").citations.isEmpty())
    }
}
