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
        assertThrows(UpdateRequired::class.java) { truncated.finish() }
        assertThrows(UpdateRequired::class.java) { DeepSeekStream().feed("data: {bad}\n\n") }
        assertEquals("", DeepSeekStream().feed("data: {\"p\":\"response/fragments\",\"o\":\"SET\",\"v\":[{\"type\":\"UNKNOWN\",\"content\":\"hidden\"}]}\n\n").last().response)
        assertThrows(UpdateRequired::class.java) { DeepSeekStream().feed("data: {\"p\":\"response/fragments\",\"o\":\"SET\",\"v\":[{\"type\":\"bad type\",\"content\":\"x\"}]}\n\n") }
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
        assertThrows(UpdateRequired::class.java) {
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
}
