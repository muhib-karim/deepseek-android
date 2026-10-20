package app.dsandroid.core.deepseek

import org.junit.Assert.*
import org.junit.Test

class PatchStreamTest {
    @Test fun separatesThinkingAndResponseAcrossArbitrarySseChunks() {
        val stream = DeepSeekStream()
        val wire = """data: {"v":{"response_message_id":9,"response":{"fragments":[{"id":1,"type":"THINK","content":"Plan"}]}}}

data: {"p":"response/fragments/-1/content","o":"APPEND","v":" carefully"}

data: {"p":"response/fragments","o":"APPEND","v":[{"id":2,"type":"RESPONSE","content":"Hello"}]}

data: {"p":"response/fragments/-1/content","o":"APPEND","v":" world"}

data: {"v":"!"}

data: {"p":"response_message_id","o":"SET","v":10}

data: [DONE]

"""
        wire.chunked(7).forEach(stream::feed)
        val result = stream.finish()
        assertEquals("Plan carefully", result.thinking)
        assertEquals("Hello world!", result.response)
        assertEquals(10L, result.responseMessageId)
        assertTrue(result.finished)
    }
    @Test fun setReplacesRatherThanDuplicatesTextAndBatchUsesRelativePaths() {
        val stream = DeepSeekStream()
        stream.feed("data: {\"p\":\"response\",\"o\":\"BATCH\",\"v\":[{\"p\":\"fragments\",\"o\":\"SET\",\"v\":[{\"type\":\"RESPONSE\",\"content\":\"old\"}]},{\"p\":\"fragments/0/content\",\"o\":\"SET\",\"v\":\"new\"},{\"p\":\"status\",\"v\":\"FINISHED\"}]}\n\n")
        assertEquals("new", stream.finish().response)
    }
    @Test fun prematureEofAndUnknownContentShapesAreNotFakeSuccesses() {
        val truncated = DeepSeekStream()
        truncated.feed("data: {\"v\":{\"response_message_id\":1}}\n\n")
        assertThrows(WebReplyInterrupted::class.java) { truncated.finish() }
        assertThrows(UpdateRequired::class.java) { DeepSeekStream().feed("data: {bad}\n\n") }
        assertEquals("", DeepSeekStream().feed("data: {\"p\":\"response/fragments\",\"o\":\"SET\",\"v\":[{\"type\":\"UNKNOWN\",\"content\":\"hidden\"}]}\n\n").last().response)
        // A fragment type this client cannot read keeps its slot and is not shown; it never ends the turn.
        assertEquals("", DeepSeekStream().feed("data: {\"p\":\"response/fragments\",\"o\":\"SET\",\"v\":[{\"type\":\"bad type\",\"content\":\"x\"}]}\n\n").last().response)
    }
    @Test fun operationOnlyPatchesChangeTheInheritedOperation() {
        val stream = DeepSeekStream()
        stream.feed("""data: {"p":"response/fragments","o":"SET","v":[{"type":"RESPONSE","content":"old"}]}

data: {"p":"response/fragments/0/content","o":"APPEND","v":" appended"}

data: {"o":"SET","v":"replacement"}

data: {"v":"final"}

data: [DONE]

""")
        assertEquals("final", stream.finish().response)
    }
    @Test fun pathOnlyPatchInheritsTheLastTextOperation() {
        val stream = DeepSeekStream()
        stream.feed("""data: {"p":"response/fragments","o":"SET","v":[{"type":"THINK","content":"A"},{"type":"RESPONSE","content":"B"}]}

data: {"p":"response/fragments/0/content","o":"APPEND","v":"1"}

data: {"p":"response/fragments/1/content","v":"2"}

data: [DONE]

""")
        assertEquals("B2", stream.finish().response)
    }
    @Test fun interruptedSnapshotCannotBecomeSuccessAtDone() {
        assertThrows(WebReplyInterrupted::class.java) {
            DeepSeekStream().feed("""data: {"v":{"response_message_id":1,"response":{"status":"INTERRUPTED","fragments":[{"type":"RESPONSE","content":"partial"}]}}}

data: [DONE]

""")
        }
    }
    @Test fun streamAuthFailureRequiresLoginWithoutEchoingServerPayload() {
        val error = assertThrows(SessionExpired::class.java) {
            DeepSeekStream().feed("data: {\"code\":401,\"message\":\"screenshot-canary-not-a-real-token\"}\n\n")
        }
        assertFalse(error.message.orEmpty().contains("canary"))
    }

    @Test fun anyNegativeIndexCountsFromTheEndOfTheFragmentList() {
        val stream = DeepSeekStream()
        stream.feed("""data: {"v":{"response":{"fragments":[{"id":1,"type":"THINK","content":"A"},{"id":2,"type":"TOOL_OPEN","status":"WIP","result":{"url":"https://example.org/a","title":"A"}},{"id":3,"type":"TOOL_OPEN","status":"WIP","result":{"url":"https://example.org/b","title":"B"}}]}}}

data: {"p":"response/fragments/-3/content","o":"APPEND","v":"1"}

data: {"p":"response/fragments/-2/status","o":"SET","v":"FINISHED"}

data: {"p":"response/fragments/-1/status","v":"FINISHED"}

data: {"p":"response/fragments","o":"APPEND","v":[{"id":4,"type":"RESPONSE","content":"Answer"}]}

data: {"p":"response/status","o":"SET","v":"FINISHED"}

""")
        val reply = stream.finish()
        assertEquals("A1", reply.thinking)
        assertEquals("Answer", reply.response)
        assertTrue((reply.timeline[1] as WebStep.Read).done)
        // An index before the first fragment is ignored for metadata but cannot place text.
        assertThrows(EndpointChanged::class.java) { DeepSeekStream().feed("data: {\"p\":\"response/fragments/-1/content\",\"v\":\"x\"}\n\n") }
        DeepSeekStream().feed("data: {\"p\":\"response/fragments/-4/status\",\"v\":\"FINISHED\"}\n\n")
    }

    @Test fun aBatchWithoutAnOperationAfterAnotherBatchIsStillABatch() {
        val stream = DeepSeekStream()
        stream.feed("""data: {"v":{"response":{"fragments":[{"id":1,"type":"RESPONSE","content":"Hi"}]}}}

data: {"p":"response/fragments/-1","o":"BATCH","v":[{"p":"content","o":"APPEND","v":" there"},{"p":"references","o":"SET","v":[]}]}

data: {"p":"response","v":[{"p":"accumulated_token_usage","v":78},{"p":"quasi_status","v":"FINISHED"}]}

data: {"p":"response/status","o":"SET","v":"FINISHED"}

""")
        assertEquals("Hi there", stream.finish().response)
    }

    @Test fun unknownPathsFieldsAndFragmentTypesNeverEndTheTurn() {
        val stream = DeepSeekStream()
        stream.feed("""data: {"v":{"response":{"fragments":[{"id":1,"type":"RESPONSE","content":"Text"}],"brand_new_field":{"a":1}},"usage":{"x":1}}}

data: {"p":"response/conversation_mode","o":"SET","v":"DEEP_SEARCH"}

data: {"p":"response/fragments/-1/brand_new_field","v":[1,2]}

data: {"p":"response/fragments","o":"APPEND","v":[{"id":2,"type":"TOOL_FUTURE","content":"Found keyword","status":"FINISHED"},{"id":3,"type":"RESPONSE","content":" more"}]}

data: {"p":"response/fragments/-2/results/0/url","v":"https://example.org"}

data: {"p":"response/status","o":"SET","v":"FINISHED"}

""")
        val reply = stream.finish()
        assertEquals("Text more", reply.response)
        assertEquals(WebStep.Tool("Found keyword", true), reply.timeline.single())
    }

    @Test fun aMarkerSplitAcrossEventsIsHiddenUntilItCanBeResolved() {
        val stream = DeepSeekStream()
        val start = stream.feed("""data: {"v":{"response":{"fragments":[{"id":5,"type":"TOOL_OPEN","status":"FINISHED","result":{"url":"https://example.org/page","title":"Page"}},{"id":6,"type":"RESPONSE","content":"Fact[refer"}]}}}

""").last()
        assertEquals("Fact", start.response)
        val done = stream.feed("""data: {"p":"response/fragments/-1","o":"BATCH","v":[{"p":"content","o":"APPEND","v":"ence:0]."},{"p":"references","o":"SET","v":[{"id":5,"type":"TOOL_OPEN"}]}]}

data: {"p":"response/status","o":"SET","v":"FINISHED"}

""").last()
        assertEquals("Fact[citation:1].", done.response)
        assertEquals(listOf(WebCitation("Page", "https://example.org/page")), done.citations)
    }

    @Test fun markersThatCannotBeResolvedAreRemovedNotShown() {
        val stream = DeepSeekStream()
        stream.feed("""data: {"v":{"response":{"fragments":[{"id":1,"type":"RESPONSE","content":"A[reference:3] B[citation:9] C[reference:x] [link](https://example.org)","references":[]}]}}}

data: {"p":"response/status","o":"SET","v":"FINISHED"}

""")
        assertEquals("A B C [link](https://example.org)", stream.finish().response)
    }
}
