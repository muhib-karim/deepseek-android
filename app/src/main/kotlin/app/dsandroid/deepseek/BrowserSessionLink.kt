package app.dsandroid.deepseek

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import app.dsandroid.MainActivity
import app.dsandroid.core.deepseek.BrowserSignIn
import app.dsandroid.core.deepseek.SessionToken
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Holds a session handed back from the browser until the account screen asks the person to confirm it. */
object BrowserSessionInbox {
    private const val PREFS = "browser_sign_in"
    private const val STARTED = "started_at"
    private val pending = MutableStateFlow<SessionToken?>(null)
    val incoming: StateFlow<SessionToken?> = pending.asStateFlow()

    /** The timestamp survives the app being closed while the person is in the browser; it is not a secret. */
    fun markStarted(context: Context, nowMillis: Long = System.currentTimeMillis()) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong(STARTED, nowMillis).apply()
    }
    fun startedAt(context: Context): Long? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(STARTED, 0L).takeIf { it > 0L }
    fun clearStarted(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(STARTED).apply()
    }
    /** Returns false when the link is not wanted now (no browser sign-in pending) or does not carry a usable session. */
    fun offer(context: Context, extra: String?, nowMillis: Long = System.currentTimeMillis()): Boolean {
        val token = BrowserSignIn.fromLink(extra, startedAt(context), nowMillis) ?: return false
        pending.value = token
        return true
    }
    fun take(): SessionToken? = pending.value.also { pending.value = null }
    /** Set when a link arrived that was not asked for (or carried no usable session), so the app can say it was ignored. */
    val ignored = MutableStateFlow(false)

    /** The default browser, so a verified DeepSeek app on the phone does not take the sign-in link instead. */
    fun browserIntent(context: Context): Intent {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(BrowserSignIn.SIGN_IN_URL)).addCategory(Intent.CATEGORY_BROWSABLE)
        val probe = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com/")).addCategory(Intent.CATEGORY_BROWSABLE)
        val browser = context.packageManager.resolveActivity(probe, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
        if (browser != null && browser != "android") intent.setPackage(browser)
        return intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}

/**
 * Receives the browser bookmark's "Open in DeepSeek Android" link. It shows nothing, never logs the session,
 * and only passes it on when a browser sign-in was started in this app in the last 30 minutes; the account
 * screen then asks the person to confirm before signing in.
 */
class SessionLinkActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val data = intent?.data
        val fromLink = data?.scheme == BrowserSignIn.LINK_SCHEME && data.host == BrowserSignIn.LINK_HOST
        val accepted = fromLink && BrowserSessionInbox.offer(this, intent?.getStringExtra(BrowserSignIn.EXTRA_TOKEN))
        intent?.removeExtra(BrowserSignIn.EXTRA_TOKEN)
        if (!accepted) BrowserSessionInbox.ignored.value = true
        startActivity(Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        finish()
    }
}
