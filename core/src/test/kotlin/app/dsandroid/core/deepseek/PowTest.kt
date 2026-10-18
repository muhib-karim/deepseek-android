package app.dsandroid.core.deepseek

import java.util.Base64
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class PowTest {
    private val challenge = PowChallenge("DeepSeekHashV1", "abcdef", "盐", 64, 2000, "signature")
    @Test fun headerIsUtf8Base64WithExactlyTheCompletionContract() {
        val decoded = String(Base64.getDecoder().decode(challenge.header(7)), Charsets.UTF_8)
        assertEquals(Json.parseToJsonElement("""{"algorithm":"DeepSeekHashV1","challenge":"abcdef","salt":"盐","answer":7,"signature":"signature","target_path":"/api/v0/chat/completion"}"""), Json.parseToJsonElement(decoded))
        assertFalse(challenge.header(7).contains('\n'))
    }
    @Test fun workerInputUsesCamelCaseExpiryAndTypedEnvelope() {
        val message = challenge.workerMessage()
        assertEquals("pow-challenge", message["type"]!!.jsonPrimitive.content)
        assertEquals(2000, message["challenge"]!!.jsonObject["expireAt"]!!.jsonPrimitive.int)
        assertFalse(message.toString().contains("expire_at"))
    }
    @Test fun changedAlgorithmsInvalidAnswersAndExpiredChallengesFailVisibly() {
        assertThrows(UpdateRequired::class.java) { challenge.copy(algorithm = "Unknown").header(1) }
        assertThrows(UpdateRequired::class.java) { challenge.header(-1) }
        assertThrows(UpdateRequired::class.java) { challenge.header(64) }
        assertThrows(UpdateRequired::class.java) { challenge.requireUsable(2_000_000) }
    }
}
