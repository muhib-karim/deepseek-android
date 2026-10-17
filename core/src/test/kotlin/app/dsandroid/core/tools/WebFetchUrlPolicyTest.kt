package app.dsandroid.core.tools

import java.net.InetAddress
import org.junit.Assert.*
import org.junit.Test

class WebFetchUrlPolicyTest {
    @Test fun rejectsUnsafeSchemesHostsAndIpLiteralsWithoutDns() {
        listOf("file:///unused", "http://example.org", "https://localhost", "https://localhost.", "https://a.localhost",
            "https://127.0.0.1", "https://10.1.2.3", "https://172.16.0.1", "https://192.168.1.1", "https://169.254.1.1",
            "https://[::1]", "https://[fc00::1]", "https://[fe80::1]", "https://[::ffff:127.0.0.1]",
            "https://2130706433", "https://127.1", "https://user:pass@example.org", "https://100.64.0.1").forEach {
            assertFalse(it, WebFetchUrlPolicy.isAllowed(it))
        }
        assertTrue(WebFetchUrlPolicy.isAllowed("https://example.org/article"))
        assertTrue(WebFetchUrlPolicy.isAllowed("https://8.8.8.8/"))
    }
    @Test fun checksResolvedAddressesToo() {
        assertFalse(WebFetchUrlPolicy.isPublicAddress(InetAddress.getByAddress(byteArrayOf(10, 0, 0, 1))))
        assertTrue(WebFetchUrlPolicy.isPublicAddress(InetAddress.getByAddress(byteArrayOf(8, 8, 8, 8))))
    }
    @Test fun extractsTitleAndTextWithoutScripts() {
        val text = WebFetchTool.extractText("<html><title>A &amp; B</title><script>secret()</script><p>Hello <b>world</b></p></html>")
        assertEquals("A & B", text.first)
        assertTrue(text.second.contains("Hello world"))
        assertFalse(text.second.contains("secret"))
    }
}
