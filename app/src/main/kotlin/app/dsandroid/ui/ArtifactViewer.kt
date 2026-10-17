package app.dsandroid.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color as AndroidColor
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.dsandroid.core.guard.WorkspacePath
import app.dsandroid.core.tools.Artifact
import app.dsandroid.core.tools.Csv
import app.dsandroid.R
import app.dsandroid.core.attachments.DocxTextExtractor
import app.dsandroid.core.attachments.PptxTextExtractor
import app.dsandroid.core.attachments.XlsxTextExtractor
import app.dsandroid.core.attachments.SpreadsheetPreview
import app.dsandroid.tools.AndroidSvgRasterizer
import java.io.ByteArrayInputStream
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private sealed interface Preview {
    data class Text(val text: String) : Preview
    data class Pdf(val pages: Int) : Preview
    data class Picture(val bitmap: Bitmap) : Preview
    data class Workbook(val sheets: List<SpreadsheetPreview>) : Preview
    data object Error : Preview
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArtifactViewer(artifact: Artifact, workspace: WorkspacePath, back: () -> Unit, share: () -> Unit, save: () -> Unit,
    openExternal: () -> Unit = {}, exportPng: () -> Unit = {}) {
    var preview by remember(artifact.path, artifact.mimeType) { mutableStateOf<Preview?>(null) }
    LaunchedEffect(artifact.path, artifact.mimeType) {
        preview = withContext(Dispatchers.IO) {
            try {
                val file = workspace.resolve(artifact.path)
                require(file.isFile && file.length() <= 20 * 1024 * 1024)
                when {
                    artifact.mimeType == "application/pdf" -> withPdf(file) { Preview.Pdf(it.pageCount) }
                    artifact.fileName.endsWith(".docx", true) -> Preview.Text(file.inputStream().use { DocxTextExtractor.extract(it) })
                    artifact.fileName.endsWith(".pptx", true) -> Preview.Text(file.inputStream().use { PptxTextExtractor.extract(it) })
                    artifact.fileName.endsWith(".xlsx", true) -> Preview.Workbook(file.inputStream().use { XlsxTextExtractor.preview(it) })
                    artifact.mimeType == "image/svg+xml" -> {
                        require(file.length() <= 2 * 1024 * 1024)
                        Preview.Picture(AndroidSvgRasterizer.bitmap(file.readText()))
                    }
                    artifact.mimeType.startsWith("image/") && artifact.mimeType != "image/svg+xml" -> {
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeFile(file.path, bounds)
                        var sample = 1
                        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1568) sample *= 2
                        Preview.Picture(requireNotNull(BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })))
                    }
                    else -> {
                        require(file.length() <= 2 * 1024 * 1024)
                        Preview.Text(file.readText())
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { Preview.Error }
        }
    }
    Scaffold(topBar = {
        TopAppBar(title = { Text(artifact.fileName, maxLines = 1) }, navigationIcon = { TextButton(onClick = back) { Text(stringResource(R.string.viewer_back)) } })
    }, bottomBar = {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = share, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.viewer_share)) }
                Button(onClick = save, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.viewer_save)) }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                TextButton(onClick = openExternal) { Text(stringResource(R.string.viewer_open)) }
                if (artifact.mimeType == "image/svg+xml") TextButton(onClick = exportPng) { Text(stringResource(R.string.viewer_export_png)) }
            }
        }
    }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (val loaded = preview) {
                null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                Preview.Error -> Text(stringResource(R.string.viewer_error), Modifier.padding(24.dp))
                is Preview.Pdf -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(loaded.pages) { page -> PdfPage(workspace, artifact.path, page) }
                }
                is Preview.Picture -> Image(loaded.bitmap.asImageBitmap(), artifact.fileName, Modifier.fillMaxSize().padding(16.dp), contentScale = ContentScale.Fit)
                is Preview.Workbook -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    items(loaded.sheets.size) { index ->
                        val sheet = loaded.sheets[index]
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(sheet.name, style = MaterialTheme.typography.titleMedium)
                            if (sheet.rows.isEmpty()) Text(stringResource(R.string.viewer_empty_csv))
                            else DataTable(sheet.rows.first(), sheet.rows.drop(1))
                        }
                    }
                    item { Text(stringResource(R.string.viewer_sheet_limit), style = MaterialTheme.typography.bodySmall) }
                }
                is Preview.Text -> when (artifact.mimeType) {
                    "text/html", "application/xhtml+xml" -> StaticWebPreview(loaded.text)
                    "text/csv" -> {
                        val rows = remember(loaded.text) { Csv.decode(loaded.text) }
                        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                            if (rows.isEmpty()) Text(stringResource(R.string.viewer_empty_csv)) else DataTable(rows.first(), rows.drop(1))
                        }
                    }
                    else -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
                        if (artifact.fileName.endsWith(".docx", true) || artifact.fileName.endsWith(".pptx", true) || artifact.fileName.endsWith(".xlsx", true)) {
                            androidx.compose.foundation.text.selection.SelectionContainer { Text(loaded.text) }
                        } else MarkdownText(loaded.text)
                    }
                }
            }
        }
    }
}

private fun <T> withPdf(file: File, block: (PdfRenderer) -> T): T =
    ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor -> PdfRenderer(descriptor).use(block) }

@Composable
private fun PdfPage(workspace: WorkspacePath, path: String, index: Int) {
    var failed by remember(path, index) { mutableStateOf(false) }
    var bitmap by remember(path, index) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(path, index) {
        try {
            bitmap = withContext(Dispatchers.IO) {
                withPdf(workspace.resolve(path)) { renderer -> renderer.openPage(index).use { page ->
                    val ratio = minOf(1200f / page.width, 1600f / page.height)
                    Bitmap.createBitmap((page.width * ratio).roundToInt().coerceAtLeast(1), (page.height * ratio).roundToInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888).also {
                        it.eraseColor(AndroidColor.WHITE)
                        page.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    }
                } }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { failed = true }
    }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        val image = bitmap
        if (image != null) Image(image.asImageBitmap(), stringResource(R.string.viewer_page, index + 1), Modifier.fillMaxWidth().aspectRatio(image.width.toFloat() / image.height), contentScale = ContentScale.FillWidth)
        else Box(Modifier.fillMaxWidth().height(260.dp), contentAlignment = Alignment.Center) {
            if (failed) Text(stringResource(R.string.viewer_page_error)) else CircularProgressIndicator()
        }
        Text(stringResource(R.string.viewer_page, index + 1), style = MaterialTheme.typography.labelSmall)
    }
}

@Suppress("DEPRECATION")
@Composable
private fun StaticWebPreview(content: String) {
    val holder = remember { arrayOfNulls<WebView>(1) }
    val document = remember(content) {
        "<!doctype html><html><head><meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">" +
            "<meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; img-src data:; style-src 'unsafe-inline'; form-action 'none'; base-uri 'none'\">" +
            "<style>body{margin:16px;overflow-wrap:anywhere}svg,img{max-width:100%;height:auto}</style></head><body>" + content + "</body></html>"
    }
    DisposableEffect(Unit) { onDispose { holder[0]?.apply { stopLoading(); destroy() }; holder[0] = null } }
    AndroidView(modifier = Modifier.fillMaxSize(), factory = { context ->
        WebView(context).apply {
            holder[0] = this
            settings.javaScriptEnabled = false
            settings.domStorageEnabled = false
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.allowFileAccessFromFileURLs = false
            settings.allowUniversalAccessFromFileURLs = false
            settings.blockNetworkLoads = true
            settings.blockNetworkImage = true
            settings.setSupportMultipleWindows(false)
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = true
                override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean = true
                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse =
                    WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
            }
            loadDataWithBaseURL(null, document, "text/html", "UTF-8", null)
        }
    })
}
