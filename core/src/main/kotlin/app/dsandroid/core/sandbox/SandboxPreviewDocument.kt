package app.dsandroid.core.sandbox

object SandboxPreviewDocument {
    fun wrap(html: String, javaScriptEnabled: Boolean, approvedNetwork: Boolean, localProject: Boolean = false): String {
        require(html.length <= 2 * 1024 * 1024) { "Preview exceeds 2 MB" }
        val remote = if (approvedNetwork) " https:" else if (localProject) " https://artifact.invalid" else ""
        val scripts = if (javaScriptEnabled) "'unsafe-inline' 'unsafe-eval'$remote" else "'none'"
        val policy = "default-src 'none'; script-src $scripts; style-src 'unsafe-inline'$remote; " +
            "img-src data: blob:$remote; font-src data:$remote; connect-src 'none'; " +
            "media-src 'none'; frame-src 'none'; child-src 'none'; worker-src 'none'; object-src 'none'; " +
            "base-uri 'none'; form-action 'none'; manifest-src 'none'"
        // First policy wins; supplied base tags, forms, frames and later weaker policies cannot relax it.
        return "<!doctype html><html><head><meta charset=\"utf-8\"><meta http-equiv=\"Content-Security-Policy\" content=\"$policy\">" +
            "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">" +
            "<style>body{margin:16px;color:#172337;background:white;font:16px sans-serif;overflow-wrap:anywhere}" +
            "svg,img{max-width:100%;height:auto}pre{white-space:pre-wrap}table{border-collapse:collapse}" +
            "th,td{border:1px solid #a2adbc;padding:8px}</style>" +
            "<meta name=\"referrer\" content=\"no-referrer\"></head><body>$html</body></html>"
    }
}
