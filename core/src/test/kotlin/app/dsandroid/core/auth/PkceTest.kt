package app.dsandroid.core.auth

import org.junit.Assert.*
import org.junit.Test

class PkceTest {
    @Test fun rfc7636AppendixB() {
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", Pkce.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
    }
    @Test fun generatedVerifiersHaveValidAlphabetLengthAndEntropy() {
        val values = (1..100).map { Pkce.verifier() }
        assertEquals(100, values.toSet().size)
        assertTrue(values.all { it.length in 43..128 && it.matches(Regex("[A-Za-z0-9._~-]+")) })
        assertTrue(values.all { !Pkce.challenge(it).contains('=') })
    }
    @Test fun invalidVerifierRejected() {
        assertThrows(IllegalArgumentException::class.java) { Pkce.challenge("short") }
    }
}
