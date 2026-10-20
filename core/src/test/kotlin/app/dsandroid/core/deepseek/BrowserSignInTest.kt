package app.dsandroid.core.deepseek

import java.util.Base64
import org.junit.Assert.*
import org.junit.Test

class BrowserSignInTest {
    private val now = 1_800_000_000_000L
    private val session = "synthetic-browser-session-0123456789"

    private fun jwt(expSeconds: Long): String {
        val enc = Base64.getUrlEncoder().withoutPadding()
        val header = enc.encodeToString("""{"alg":"none"}""".toByteArray())
        val body = enc.encodeToString("""{"exp":$expSeconds}""".toByteArray())
        return "$header.$body.signature"
    }

    @Test fun bookmarkletTargetsOnlyThisPackageAndReadsOnlyTheSession() {
        val link = BrowserSignIn.bookmarklet("app.dsandroid")
        assertTrue(link.startsWith("javascript:"))
        assertFalse(link.contains(' '))
        val js = link.removePrefix("javascript:").replace("%20", " ").replace("%25", "%")
        assertTrue(js.contains("intent://session#Intent;scheme=dsandroid;package=app.dsandroid;S.token="))
        assertTrue(js.contains("location.hostname!=='chat.deepseek.com'"))
        assertTrue(js.contains("localStorage.getItem('userToken')"))
        // No network calls and no other storage reads.
        listOf("fetch(", "XMLHttpRequest", "sendBeacon", "document.cookie", "localStorage.key", "Object.keys(localStorage").forEach {
            assertFalse(it, js.contains(it))
        }
        assertEquals(js.count { it == '(' }, js.count { it == ')' })
        assertEquals(js.count { it == '{' }, js.count { it == '}' })
        assertEquals(0, js.count { it == '"' })
        assertEquals(0, js.count { it == '\'' } % 2)
    }

    @Test fun bookmarkletRejectsAnInjectedPackageName() {
        listOf("", "app", "app.ds;S.x=1", "app.ds'x", "app ds.x", "1app.ds").forEach {
            assertThrows(IllegalArgumentException::class.java) { BrowserSignIn.bookmarklet(it) }
        }
        assertTrue(BrowserSignIn.bookmarklet("app.dsandroid.debug").contains("package=app.dsandroid.debug;"))
    }

    @Test fun linkIsTakenOnlyWhileABrowserSignInIsPending() {
        assertEquals(session, BrowserSignIn.fromLink(session, now - 60_000, now)?.bearer())
        assertEquals(session, BrowserSignIn.fromLink("""{"value":"$session"}""", now - 60_000, now)?.bearer())
        assertNull(BrowserSignIn.fromLink(session, null, now))
        assertNull(BrowserSignIn.fromLink(session, now - BrowserSignIn.WINDOW_MILLIS - 1, now))
        assertNull(BrowserSignIn.fromLink(session, now + 60_000, now))
    }

    @Test fun garbageExpiredOrShortSessionsAreRejected() {
        listOf(null, "", "null", "{}", "short", "bad\r\nheader-value-0123456789", "<script>alert(1)</script>", "x".repeat(20000)).forEach {
            assertNull(it, BrowserSignIn.fromLink(it, now - 1000, now))
            assertNull(it, BrowserSignIn.fromPaste(it, now))
        }
        assertNull(BrowserSignIn.fromPaste(jwt(now / 1000 - 60), now))
        assertNotNull(BrowserSignIn.fromPaste(jwt(now / 1000 + 3600), now))
    }

    @Test fun pasteAcceptsTheCommonCopiedShapes() {
        listOf(session, "  $session\n", "\"$session\"", "'$session'", "Bearer $session", """{"value":"$session","__version":"0"}""").forEach {
            assertEquals(it, session, BrowserSignIn.fromPaste(it, now)?.bearer())
        }
    }

    @Test fun pendingWindowBounds() {
        assertTrue(BrowserSignIn.isPending(now, now))
        assertTrue(BrowserSignIn.isPending(now - BrowserSignIn.WINDOW_MILLIS, now))
        assertFalse(BrowserSignIn.isPending(now - BrowserSignIn.WINDOW_MILLIS - 1, now))
        assertFalse(BrowserSignIn.isPending(null, now))
    }
}
