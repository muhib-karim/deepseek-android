package app.dsandroid.core.auth

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

object OAuthState {
    fun create(): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })

    fun matches(expected: String, actual: String?): Boolean = expected.isNotEmpty() && actual != null &&
        MessageDigest.isEqual(expected.toByteArray(Charsets.UTF_8), actual.toByteArray(Charsets.UTF_8))
}
