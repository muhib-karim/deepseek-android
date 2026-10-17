package app.dsandroid.core.auth

import org.junit.Assert.*
import org.junit.Test

class OAuthStateTest {
    @Test fun randomStateAndExactComparison() {
        val state = OAuthState.create()
        assertNotEquals(state, OAuthState.create())
        assertTrue(OAuthState.matches(state, state))
        assertFalse(OAuthState.matches(state, state + "x"))
        assertFalse(OAuthState.matches(state, null))
        assertFalse(OAuthState.matches("", ""))
    }
}
