package app.dsandroid.core.deepseek

import org.junit.Assert.*
import org.junit.Test

/** Event shapes recorded from the live web service with a test account (Oct 2026), trimmed. */
class LiveShapeStreamTest {
    private fun run(wire: String): WebReply {
        val stream = DeepSeekStream()
        stream.feed(wire)
        return stream.finish()
    }

    @Test fun searchReplyWithMetadataEventsAndSearchFragment() {
        val reply = run("""event: ready
data: {"request_message_id":1,"response_message_id":2,"model_type":"default"}

event: update_session
data: {"updated_at":1792313325.7790549}

data: {"v":{"response":{"message_id":2,"parent_id":1,"role":"ASSISTANT","thinking_enabled":false,"status":"WIP","accumulated_token_usage":0,"search_enabled":true,"fragments":[{"id":2,"type":"SEARCH","content":"","queries":[],"results":[]}]}}}

data: {"p":"response/fragments/-1/results","v":[{"url":"https://example.org/a","title":"A","snippet":"s"}]}

data: {"p":"response/fragments/-1","o":"BATCH","v":[{"p":"status","v":"FINISHED"},{"p":"content","v":"Found 12 web pages"}]}

data: {"p":"response/conversation_mode","o":"SET","v":"SEARCH"}

data: {"p":"response/has_pending_fragment","v":true}

data: {"p":"response","o":"BATCH","v":[{"p":"fragments","o":"APPEND","v":[{"id":3,"type":"RESPONSE","content":"The","references":[],"stage_id":2}]},{"p":"has_pending_fragment","o":"SET","v":false}]}

data: {"p":"response/fragments/-1/content","o":"APPEND","v":" latest"}

data: {"v":" is 2.3."}

data: {"p":"response","o":"BATCH","v":[{"p":"accumulated_token_usage","v":78},{"p":"quasi_status","v":"FINISHED"}]}

data: {"p":"response/status","o":"SET","v":"FINISHED"}

event: update_session
data: {"updated_at":1792313326.649045}

event: title
data: {"content":"Latest Kotlin stable version"}

event: close
data: {"click_behavior":"none","auto_resume":false}

""")
        assertEquals("The latest is 2.3.", reply.response)
        assertEquals("", reply.thinking)
        assertEquals(2L, reply.responseMessageId)
        assertEquals(1L, reply.requestMessageId)
        assertTrue(reply.finished)
    }

    @Test fun deepThinkReplyWithElapsedTimeField() {
        val reply = run("""event: ready
data: {"request_message_id":1,"response_message_id":2,"model_type":"default"}

event: update_session
data: {"updated_at":1792313297.449555}

data: {"v":{"response":{"message_id":2,"parent_id":1,"role":"ASSISTANT","thinking_enabled":true,"status":"WIP","search_enabled":false,"fragments":[{"id":2,"type":"THINK","content":"We","elapsed_secs":null}]}}}

data: {"p":"response/fragments/-1/content","o":"APPEND","v":" need"}

data: {"v":" one line."}

data: {"p":"response/fragments/-1/elapsed_secs","o":"SET","v":1.2}

data: {"p":"response/fragments","o":"APPEND","v":[{"id":3,"type":"RESPONSE","content":"391"}]}

data: {"p":"response/fragments/-1/content","o":"APPEND","v":" = 17 x 23."}

data: {"p":"response","o":"BATCH","v":[{"p":"accumulated_token_usage","v":87},{"p":"quasi_status","v":"FINISHED"}]}

data: {"p":"response/status","o":"SET","v":"FINISHED"}

event: title
data: {"content":"391 not prime"}

event: close
data: {"click_behavior":"none","auto_resume":false}

""")
        assertEquals("We need one line.", reply.thinking)
        assertEquals("391 = 17 x 23.", reply.response)
        assertTrue(reply.finished)
    }
}
