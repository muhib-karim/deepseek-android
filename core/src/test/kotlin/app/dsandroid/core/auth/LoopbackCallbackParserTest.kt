package app.dsandroid.core.auth

import org.junit.Assert.*
import org.junit.Test

class LoopbackCallbackParserTest {
    @Test fun decodesCodeAndValidatesState() {
        assertEquals("a+b c", LoopbackCallbackParser.parse("GET /oauth/callback?code=a%2Bb+c&state=expected HTTP/1.1", "expected"))
    }
    @Test fun rejectsWrongPathStateMethodDuplicateAndError() {
        listOf(
            "GET /other?code=a&state=expected HTTP/1.1",
            "GET /oauth/callback?code=a&state=wrong HTTP/1.1",
            "POST /oauth/callback?code=a&state=expected HTTP/1.1",
            "GET /oauth/callback?code=a&state=expected&state=expected HTTP/1.1",
            "GET /oauth/callback?error=access_denied&state=expected HTTP/1.1",
            "GET /oauth/callback?state=expected HTTP/1.1",
            "GET /oauth/callback?code=a&state=expected#fragment HTTP/1.1"
        ).forEach { line -> assertThrows(line, IllegalArgumentException::class.java) { LoopbackCallbackParser.parse(line, "expected") } }
    }
}
