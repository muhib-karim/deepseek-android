package app.dsandroid.ui

import android.webkit.WebView
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.dsandroid.R
import app.dsandroid.core.guard.WorkspacePath
import app.dsandroid.core.tools.Artifact
import app.dsandroid.core.tools.SvgSanitizer
import app.dsandroid.sandbox.SandboxWebViews
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Inline charts share the same network-free preview boundary as the canvas. */
@Composable
fun InlineSvgArtifact(artifact: Artifact, workspace: WorkspacePath, open: () -> Unit) {
    var content by remember(artifact.path) { mutableStateOf<String?>(null) }
    var failed by remember(artifact.path) { mutableStateOf(false) }
    LaunchedEffect(artifact.path) {
        try {
            content = withContext(Dispatchers.IO) {
                val file = workspace.resolve(artifact.path)
                require(file.isFile && file.length() <= 2 * 1024 * 1024)
                SvgSanitizer.sanitize(file.readText(Charsets.UTF_8))
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { failed = true }
    }
    val description = stringResource(R.string.chart_preview, artifact.fileName)
    Box(Modifier.fillMaxWidth().height(220.dp).semantics { contentDescription = description }, contentAlignment = Alignment.Center) {
        val svg = content
        when {
            failed -> Text(stringResource(R.string.viewer_error), color = MaterialTheme.colorScheme.error)
            svg == null -> CircularProgressIndicator()
            else -> key(artifact.path, svg) {
                val holder = remember { arrayOfNulls<WebView>(1) }
                DisposableEffect(Unit) {
                    onDispose { holder[0]?.apply { stopLoading(); destroy() }; holder[0] = null }
                }
                AndroidView(modifier = Modifier.fillMaxSize(), factory = { context ->
                    SandboxWebViews.create(context, javaScriptEnabled = false, onFailure = { failed = true }).also { webView ->
                        holder[0] = webView
                        // The card owns taps; charts do not navigate or receive gestures.
                        webView.isFocusable = false
                        webView.isFocusableInTouchMode = false
                        SandboxWebViews.loadPreview(webView, svg)
                    }
                })
            }
        }
        Box(Modifier.matchParentSize().clickable(onClick = open))
    }
}
