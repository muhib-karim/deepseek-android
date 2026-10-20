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
    @Test fun changedAlgorithmsInvalidAnswersAndExpiredChallengesFailVisibly() {
        assertThrows(UpdateRequired::class.java) { challenge.copy(algorithm = "Unknown").header(1) }
        assertThrows(UpdateRequired::class.java) { challenge.header(-1) }
        assertThrows(UpdateRequired::class.java) { challenge.header(64) }
        challenge.requireUsable(1999)
        assertThrows(UpdateRequired::class.java) { challenge.requireUsable(2000) }
    }

    @Test fun realWorldMillisecondsExpiryIsEnforced() {
        val live = challenge.copy(expireAt = 1_792_308_235_105L)
        live.requireUsable(1_792_308_235_104L)
        assertThrows(UpdateRequired::class.java) { live.requireUsable(1_792_308_235_105L) }
    }
    @Test fun malformedChallengeFieldTypesAreTypedAndSanitized() {
        val valid = Json.parseToJsonElement("""{"algorithm":"DeepSeekHashV1","challenge":"abcdef","salt":"salt","difficulty":64,"expire_at":9999999999999,"signature":"signature"}""").jsonObject
        listOf("challenge", "salt", "signature").forEach { key ->
            val invalid = JsonObject(valid + (key to JsonPrimitive(false)))
            val error = assertThrows(EndpointChanged::class.java) { PowChallenge.parse(invalid) }
            assertNull(error.cause)
        }
        listOf("difficulty", "expire_at").forEach { key ->
            val invalid = JsonObject(valid + (key to JsonPrimitive(valid[key]!!.jsonPrimitive.content)))
            assertThrows(EndpointChanged::class.java) { PowChallenge.parse(invalid) }
        }
    }

}
