package app.dsandroid.core.deepseek

import org.junit.Assert.*
import org.junit.Test

/**
 * Streams recorded from the live web service on 2026-10-20 with a dedicated test account (file ids and signed paths
 * replaced). think_search.sse is the Think + Search turn whose negative indices and relative BATCH paths ended every
 * Search turn in v1.0.0; search_only.sse uses the older SEARCH fragment with [citation:N]; chat_file.sse carries the
 * update_file event a completion with an uploaded file sends.
 */
class LiveSearchStreamTest {
    private fun wire(name: String) = requireNotNull(javaClass.getResource("/live-2026-10-20/$name")) { "missing $name" }.readText()
    private fun replay(name: String, chunk: Int = 4096): WebReply {
        val stream = DeepSeekStream()
        wire(name).chunked(chunk).forEach { stream.feed(it) }
        return stream.finish()
    }

    @Test fun thinkAndSearchFinishesWithTheAnswerAndItsIds() {
        val reply = replay("think_search.sse")
        assertTrue(reply.finished)
        assertEquals(1L, reply.requestMessageId)
        assertEquals(2L, reply.responseMessageId)
        assertEquals(4.078394319, reply.thinkingSeconds!!, 1e-6)
        assertTrue(reply.thinking.startsWith("The user wants the latest news headline about NASA today"))
        assertEquals(3, reply.thinking.split("\n\n").size)
    }

    @Test fun aReferenceMarkerBecomesTheNumberOfThePageItPointsTo() {
        val reply = replay("think_search.sse")
        // [reference:0] -> the RESPONSE fragment's references[0] = TOOL_OPEN fragment 7, the third page read (not search result 0).
        assertEquals("NASA Prepares for Spacewalks & Advanced Biomedical Research[citation:3]", reply.response)
        assertEquals(3, reply.citations.size)
        assertEquals("https://www.tomorrowsworldtoday.com/space/nasa-prepares-for-spacewalks-advanced-biomedical-research/", reply.citations[2].url)
        assertEquals("Tomorrow's World Today", reply.citations[2].site)
        assertEquals("https://cdn.deepseek.com/site-icons/tomorrowsworldtoday.com", reply.citations[2].icon)
        assertFalse(reply.response.contains("[reference:"))
    }

    @Test fun theTimelineHasEveryThinkingStageAndSearchStepInOrder() {
        val timeline = replay("think_search.sse").timeline
        assertEquals(listOf("think", "search", "think", "read", "think"), timeline.map {
            when (it) { is WebStep.Think -> "think"; is WebStep.Search -> "search"; is WebStep.Read -> "read"; is WebStep.Tool -> "tool" }
        })
        val search = timeline[1] as WebStep.Search
        assertEquals("Found 20 web pages", search.label)
        assertEquals(listOf("NASA news October 21 2026", "latest NASA headline today"), search.queries)
        assertEquals(20, search.results.size)
        assertTrue(search.done)
        val read = timeline[3] as WebStep.Read
        assertEquals(3, read.pages.size)
        // Statuses arrive as response/fragments/-2/status and -3/status: every page read ends FINISHED.
        assertTrue(read.done)
        assertEquals(1.990561364, (timeline[2] as WebStep.Think).seconds!!, 1e-9)
    }

    @Test fun oneCharacterChunksGiveTheSameReply() {
        val whole = replay("think_search.sse")
        val split = replay("think_search.sse", chunk = 1)
        assertEquals(whole, split)
    }

    @Test fun titleAndSessionTimeAreReadEvenAfterTheReplyFinished() {
        val reply = replay("think_search.sse")
        assertEquals("NASA spacewalk headline", reply.title)
        assertEquals(1_792_530_214_874L, reply.sessionUpdatedAt)
    }

    @Test fun searchOnlyCitationsKeepTheResultNumbersTheServiceGave() {
        val reply = replay("search_only.sse")
        assertEquals("The tallest building in the world is the Burj Khalifa in Dubai, at 828 meters (2,717 feet)[citation:1][citation:4][citation:8].",
            reply.response)
        assertEquals(12, reply.citations.size)
        assertEquals("https://www.skyscrapercenter.com/buildings", reply.citations[3].url)
        val search = reply.timeline.single() as WebStep.Search
        assertEquals("Found 12 web pages", search.label)
        assertEquals("", reply.thinking)
        assertNull(reply.thinkingSeconds)
    }

    @Test fun aReplyAboutAnUploadedFileIgnoresTheUpdateFileEvent() {
        val reply = replay("chat_file.sse")
        assertEquals("Earth", reply.response)
        assertTrue(reply.finished)
        assertTrue(reply.timeline.isEmpty())
        assertEquals("Largest planet in file", reply.title)
    }

    @Test fun plainRepliesStayWithoutATimeline() {
        val reply = DeepSeekStream().apply {
            feed("data: {\"v\":{\"response\":{\"fragments\":[{\"id\":1,\"type\":\"THINK\",\"content\":\"Plan\",\"elapsed_secs\":2.0}]}}}\n\n" +
                "data: {\"p\":\"response/fragments\",\"o\":\"APPEND\",\"v\":[{\"id\":2,\"type\":\"RESPONSE\",\"content\":\"Done\"}]}\n\n" +
                "data: {\"p\":\"response/status\",\"o\":\"SET\",\"v\":\"FINISHED\"}\n\n")
        }.finish()
        assertEquals("Plan", reply.thinking)
        assertEquals("Done", reply.response)
        assertTrue("Think-only replies render from thinking and thinkingSeconds", reply.timeline.isEmpty())
    }
}
