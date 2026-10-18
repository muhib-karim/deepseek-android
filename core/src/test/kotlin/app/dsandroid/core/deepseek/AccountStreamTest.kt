package app.dsandroid.core.deepseek

import org.junit.Assert.*
import org.junit.Test

class AccountStreamTest {
    @Test fun preservesBothServerMessageIdsAndIncrementalThinkingAndAnswer() {
        val stream = DeepSeekStream()
        val wire = """data: {"v":{"request_message_id":10,"response_message_id":11,"response":{"status":"WIP","fragments":[{"id":1,"type":"THINK","content":"Plan"}]}}}

data: {"p":"response/fragments/-1/content","o":"APPEND","v":" carefully"}

data: {"p":"response/fragments","o":"APPEND","v":[{"id":2,"type":"RESPONSE","content":"Hello"}]}

data: {"p":"response/fragments/-1/content","o":"APPEND","v":" world"}

data: {"p":"response/status","o":"SET","v":"FINISHED"}

"""
        val snapshots = wire.chunked(3).flatMap(stream::feed)
        assertEquals("Plan", snapshots.first().thinking)
        assertEquals("Plan carefully", snapshots[1].thinking)
        assertEquals("Hello", snapshots[2].response)
        val final = stream.finish()
        assertEquals(10L, final.requestMessageId)
        assertEquals(11L, final.responseMessageId)
        assertEquals("Hello world", final.response)
        assertTrue(final.finished)
    }

    @Test fun idsOnTheOuterPatchEnvelopeAreAlsoPreserved() {
        val stream = DeepSeekStream()
        stream.feed("""data: {"request_message_id":7,"response_message_id":8,"v":{"response":{"fragments":[{"type":"RESPONSE","content":"Answer"}]}}}

data: [DONE]

""")
        assertEquals(7L, stream.finish().requestMessageId)
        assertEquals(8L, stream.finish().responseMessageId)
    }

    @Test fun legacyDeltaEventsKeepThinkingSeparateAndFinishOnStop() {
        val stream = DeepSeekStream()
        stream.feed("""data: {"request_message_id":4,"response_message_id":5,"choices":[{"delta":{"reasoning_content":"Think"},"finish_reason":null}]}

data: {"choices":[{"delta":{"content":"Answer"},"finish_reason":null}]}

data: {"choices":[{"delta":{},"finish_reason":"stop"}]}

""")
        assertEquals("Think", stream.finish().thinking)
        assertEquals("Answer", stream.finish().response)
        assertEquals(4L, stream.finish().requestMessageId)
    }

    @Test fun namedReadyAndFinishEventsAllowMetadataAndCloseWithoutDoneMarker() {
        val stream = DeepSeekStream()
        stream.feed(""": heartbeat

event: ready
data: {}

event: update
data: {"v":{"response_message_id":2,"response":{"fragments":[{"type":"RESPONSE","content":"Done"}]}}}

event: finish
data: {}

""")
        assertTrue(stream.finish().finished)
        assertEquals("Done", stream.finish().response)
    }

    @Test fun malformedResponseFieldsAndFramingAreSafeTypedFailures() {
        listOf(
            "data: {\"v\":{\"response_message_id\":\"invalid\"}}\n\n",
            "data: {\"v\":{\"request_message_id\":null}}\n\n",
            "data: {\"v\":{\"response\":[]}}\n\n",
            "data: {\"v\":{\"response\":{\"fragments\":[{\"type\":\"RESPONSE\",\"content\":42}]}}}\n\n",
            "data: {\"choices\":[{\"delta\":{\"content\":{}}}]}\n\n",
            "data: {\"p\":\"response/status\",\"v\":\"UNKNOWN\"}\n\n",
            "data: {\"p\":\"response/fragments/0/content\",\"v\":\"uninitialized\"}\n\n",
            "data: {\"p\":\"response/fragments\",\"v\":\"wrong-shape\"}\n\n",
            "data: " + "x".repeat(2 * 1024 * 1024 + 1),
        ).forEach { wire ->
            val error = assertThrows(EndpointChanged::class.java) { DeepSeekStream().feed(wire) }
            assertEquals(EndpointChanged().message, error.message)
            assertNull(error.cause)
        }
    }

    @Test fun authCodesInsidePatchValuesAndBusinessDataRequireSignIn() {
        listOf(40001, 40002, 40003).forEach { code ->
            listOf("""{"v":{"code":$code}}""", """{"data":{"biz_code":$code}}""", """{"error":{"code":$code}}""").forEach { json ->
                val error = assertThrows(SessionExpired::class.java) { DeepSeekStream().feed("data: $json\n\n") }
                assertEquals("Your DeepSeek session expired. Please sign in again.", error.message)
            }
        }
    }
}
