package app.dsandroid.deepseek

import android.content.Context
import android.os.Build
import android.webkit.WebView
import app.dsandroid.BuildConfig

/**
 * A short report a person can paste to whoever helps them when sign-in does not work on their phone.
 * It holds versions and the last page error only: no account, cookie, session or address query.
 */
object SignInDiagnostics {
    fun text(context: Context, browser: DeepSeekWebView?): String {
        val webView = runCatching { WebView.getCurrentWebViewPackage() }.getOrNull()
        val pageState = when {
            browser == null -> "not opened"
            browser.pageLoaded -> "loaded"
            else -> "not loaded"
        }
        return lines(
            appVersion = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            android = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            device = "${Build.MANUFACTURER} ${Build.MODEL}",
            webView = webView?.let { "${it.packageName} ${it.versionName}" } ?: "unknown",
            signInPage = pageState,
            lastError = browser?.lastError ?: "none",
            browserSignInStarted = BrowserSessionInbox.startedAt(context) != null,
        )
    }

    internal fun lines(appVersion: String, android: String, device: String, webView: String, signInPage: String, lastError: String,
        browserSignInStarted: Boolean): String = listOf(
        "DeepSeek Android diagnostics",
        "App: $appVersion",
        "Android: $android",
        "Device: $device",
        "WebView: $webView",
        "Sign-in page: $signInPage",
        "Last page error: $lastError",
        "Browser sign-in started: ${if (browserSignInStarted) "yes" else "no"}",
    ).joinToString("\n")
}
