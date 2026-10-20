package app.dsandroid.core.deepseek

/**
 * Signing in through the phone's own browser, for accounts that use Google (Google does not allow its
 * sign-in inside an app's embedded browser). The person signs in at chat.deepseek.com in their browser and
 * a bookmark they saved hands the page's session back to this app on an explicit tap. Pure functions, so
 * the rules are covered by JVM tests.
 */
object BrowserSignIn {
    /** Address opened in the browser. */
    const val SIGN_IN_URL = "${DeepSeekOrigin.URL}/sign_in"
    /** The string extra the browser link carries the session in (never the URI, so it is not logged as a link). */
    const val EXTRA_TOKEN = "token"
    const val LINK_SCHEME = "dsandroid"
    const val LINK_HOST = "session"
    /** A session handed back from the browser is only taken while a browser sign-in started in the app is this recent. */
    const val WINDOW_MILLIS = 30L * 60 * 1000
    private val PACKAGE = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")

    /**
     * The bookmark's address. On chat.deepseek.com it adds two buttons to the page: one opens this app with the
     * session (an explicit intent to this package only, so no other app can receive it) and one copies the
     * session for the app's "Paste session" field. It reads only localStorage "userToken" and sends nothing anywhere.
     */
    fun bookmarklet(packageName: String): String {
        require(PACKAGE.matches(packageName)) { "Unsupported package name." }
        val js = "(function(){" +
            "if(location.hostname!=='chat.deepseek.com'){alert('Open chat.deepseek.com and sign in first.');return;}" +
            "var t=localStorage.getItem('userToken');" +
            "if(!t){alert('Sign in to DeepSeek on this page first, then open this bookmark again.');return;}" +
            "var v=t;try{var j=JSON.parse(t);if(j&&typeof j.value==='string')v=j.value;}catch(e){}" +
            "var old=document.getElementById('dsandroid-handoff');if(old)old.remove();" +
            "var box=document.createElement('div');box.id='dsandroid-handoff';" +
            "box.style.cssText='position:fixed;left:12px;right:12px;bottom:20px;z-index:2147483647;display:flex;flex-direction:column;gap:8px';" +
            "function btn(label){var b=document.createElement('a');b.textContent=label;" +
            "b.style.cssText='display:block;padding:14px;border-radius:12px;background:#4d6bfe;color:#fff;font:600 16px sans-serif;text-align:center;text-decoration:none';" +
            "box.appendChild(b);return b;}" +
            "var open=btn('Open in DeepSeek Android');" +
            "open.href='intent://$LINK_HOST#Intent;scheme=$LINK_SCHEME;package=$packageName;S.$EXTRA_TOKEN='+encodeURIComponent(v)+';end';" +
            "var copy=btn('Copy session instead');copy.href='#';" +
            "copy.onclick=function(e){e.preventDefault();navigator.clipboard.writeText(v).then(function(){alert('Copied. In the app, tap Paste session.');},function(){prompt('Copy this, then tap Paste session in the app:',v);});};" +
            "document.body.appendChild(box);})();"
        // Spaces are percent-encoded so the address survives being saved as a bookmark; browsers decode it before running it.
        return "javascript:" + js.replace("%", "%25").replace(" ", "%20")
    }

    /** A session handed back by the browser link: taken only if a browser sign-in was started recently. */
    fun fromLink(extra: String?, startedAtMillis: Long?, nowMillis: Long): SessionToken? {
        if (!isPending(startedAtMillis, nowMillis)) return null
        return usable(SessionToken.parse(extra), nowMillis)
    }

    /** What a person pastes: the session itself, the page's stored JSON, or either with a "Bearer " prefix or quotes. */
    fun fromPaste(text: String?, nowMillis: Long): SessionToken? {
        var raw = text?.trim() ?: return null
        if (raw.startsWith("Bearer ", ignoreCase = true)) raw = raw.substring(7).trim()
        if (raw.length >= 2 && raw.first() == '\'' && raw.last() == '\'') raw = raw.substring(1, raw.length - 1).trim()
        return usable(SessionToken.parse(raw), nowMillis)
    }

    fun isPending(startedAtMillis: Long?, nowMillis: Long): Boolean =
        startedAtMillis != null && nowMillis >= startedAtMillis && nowMillis - startedAtMillis <= WINDOW_MILLIS

    private fun usable(token: SessionToken?, nowMillis: Long): SessionToken? =
        token?.takeIf { !it.isExpired(nowMillis) && it.bearer().length >= 16 }
}
