package app.dsandroid.auth

import org.junit.Assert.*
import org.junit.Test

class AccountTokenParserTest {
    @Test fun acceptsOnlyNonemptyStringTokensAndSupportsAuthTokenFallback() {
        assertEquals("test-value", AccountTokenParser.parse("""{"access_token":"test-value"}"""))
        assertEquals("fallback-value", AccountTokenParser.parse("""{"access_token":null,"auth_token":"fallback-value"}"""))
        assertEquals("fallback-value", AccountTokenParser.parse("""{"access_token":false,"auth_token":"fallback-value"}"""))
    }
    @Test fun rejectsNullNumbersBooleansEmptyAndInjectedHeaders() {
        listOf("{}", """{"access_token":null}""", """{"access_token":123}""", """{"access_token":true}""",
            """{"access_token":""}""", """{"access_token":"bad\r\nheader"}""").forEach { response ->
            assertThrows(response, IllegalArgumentException::class.java) { AccountTokenParser.parse(response) }
        }
    }
}
