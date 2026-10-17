package app.dsandroid.core.llm

import org.junit.Assert.*
import org.junit.Test

class AuthProviderTest {
    @Test fun accountTokenNeverUsesBearer() {
        assertEquals(mapOf("x-dsh-auth-token" to "test-value"), DeepSeekAccountAuth("test-value").headers())
        assertEquals(mapOf("Authorization" to "Bearer test-value"), ApiKeyAuth("test-value").headers())
        assertEquals(emptyMap<String, String>(), CustomEndpointAuth("").headers())
        assertEquals(mapOf("Authorization" to "Bearer test-value"), CustomEndpointAuth("test-value").headers())
    }
    @Test fun rejectsHeaderInjection() {
        assertThrows(IllegalArgumentException::class.java) { ApiKeyAuth("bad\r\ninjected") }
    }
    @Test fun identityIsHonestAndIncludesLocale() {
        val headers = ClientIdentity("0.1.0", "en", 3600).headers()
        assertEquals("DeepSeek-Android-Unofficial/0.1.0 (Android)", headers["User-Agent"])
        assertEquals("android", headers["x-dsh-client-platform"])
        assertEquals("DeepSeek-Android-Unofficial", headers["x-dsh-client-name"])
        assertEquals("en", headers["x-dsh-client-language"])
        assertEquals("3600", headers["x-dsh-client-timezone-offset"])
    }
}
