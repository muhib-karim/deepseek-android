package app.dsandroid.deepseek

import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.dsandroid.MainActivity
import app.dsandroid.core.deepseek.BrowserSignIn
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The browser hand-off receiver, driven with real intents. The app's main screen is intercepted, not opened. */
@RunWith(AndroidJUnit4::class)
class BrowserSignInLinkTest {
    private val app = ApplicationProvider.getApplicationContext<Context>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val session = "synthetic-browser-link-session-0123456789"
    private lateinit var main: Instrumentation.ActivityMonitor

    private fun link(token: String?) = Intent(Intent.ACTION_VIEW, Uri.parse("dsandroid://session"))
        .setPackage(app.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        .apply { if (token != null) putExtra(BrowserSignIn.EXTRA_TOKEN, token) }

    private fun waitFor(condition: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + 10_000
        while (!condition()) { check(SystemClock.uptimeMillis() < end) { "Timed out" }; Thread.sleep(100) }
    }

    @Before fun setUp() {
        main = instrumentation.addMonitor(MainActivity::class.java.name, null, true)
        BrowserSessionInbox.take(); BrowserSessionInbox.ignored.value = false; BrowserSessionInbox.clearStarted(app)
    }
    @After fun tearDown() {
        instrumentation.removeMonitor(main)
        BrowserSessionInbox.take(); BrowserSessionInbox.ignored.value = false; BrowserSessionInbox.clearStarted(app)
    }

    @Test fun bookmarkletIntentParsesToThisReceiverWithTheSession() {
        val uri = "intent://session#Intent;scheme=dsandroid;package=${app.packageName};S.token=${Uri.encode(session)};end"
        val intent = Intent.parseUri(uri, Intent.URI_INTENT_SCHEME)
        assertEquals("dsandroid://session", intent.data.toString())
        assertEquals(app.packageName, intent.`package`)
        assertEquals(session, intent.getStringExtra(BrowserSignIn.EXTRA_TOKEN))
        intent.addCategory(Intent.CATEGORY_BROWSABLE)
        val targets = app.packageManager.queryIntentActivities(intent, 0).map { it.activityInfo.name }
        assertEquals(listOf(SessionLinkActivity::class.java.name), targets)
    }

    @Test fun aPendingBrowserSignInTakesTheSession() {
        BrowserSessionInbox.markStarted(app)
        app.startActivity(link(session))
        waitFor { BrowserSessionInbox.incoming.value != null }
        assertEquals(session, BrowserSessionInbox.take()?.bearer())
        assertFalse(BrowserSessionInbox.ignored.value)
        waitFor { main.hits >= 1 }
    }

    @Test fun anUnrequestedLinkIsIgnored() {
        app.startActivity(link(session))
        waitFor { BrowserSessionInbox.ignored.value }
        assertNull(BrowserSessionInbox.incoming.value)
    }

    @Test fun garbageOrMissingSessionsAreIgnored() {
        BrowserSessionInbox.markStarted(app)
        for (bad in listOf("<script>alert(1)</script>", "short", null)) {
            BrowserSessionInbox.ignored.value = false
            app.startActivity(link(bad))
            waitFor { BrowserSessionInbox.ignored.value }
            assertNull(BrowserSessionInbox.incoming.value)
        }
    }
}
