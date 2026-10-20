package app.dsandroid.deepseek

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color as AndroidColor
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.dsandroid.core.agent.ToolStatus
import app.dsandroid.core.agent.TurnStep
import app.dsandroid.core.attachments.DocxTextExtractor
import app.dsandroid.core.tools.Artifact
import app.dsandroid.ui.AppIcon
import app.dsandroid.ui.Glyph
import app.dsandroid.ui.MarkdownText
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

/** Ordered agent turn: reasoning, compact tool rows and answer text, in the order they happened. */
@Composable
fun AgentTimeline(messageId: String, steps: List<TurnStep>, working: Boolean, onRunCode: ((String) -> Unit)?,
    onPreviewCode: ((String, String) -> Unit)? = null, onEditCode: ((String, String) -> Unit)? = null) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        steps.forEachIndexed { index, step ->
            when (step) {
                is TurnStep.Reasoning -> ReasoningRow("$messageId-$index", step.text, working && !step.complete)
                is TurnStep.ToolCall -> ToolStepRow(step)
                is TurnStep.Answer -> if (step.text.isNotBlank()) MarkdownText(step.text.trim(), onRunCode = onRunCode, onPreviewCode = onPreviewCode, onEditCode = onEditCode)
            }
        }
    }
}

@Composable
private fun ReasoningRow(key: String, text: String, active: Boolean) {
    var expanded by rememberSaveable(key) { mutableStateOf(false) }
    Row(Modifier.clickable { expanded = !expanded }.heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(if (active) "Thinking\u2026" else "Thought", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        AppIcon(Glyph.CHEVRON, if (expanded) "Collapse reasoning" else "Expand reasoning", Modifier.size(18.dp).rotate(if (expanded) 180f else 0f))
    }
    if (expanded) Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
}

/** One tool call as a single compact line; tapping shows its input and result. */
@Composable
fun ToolStepRow(step: TurnStep.ToolCall) {
    var expanded by rememberSaveable(step.id) { mutableStateOf(false) }
    val label = toolLabel(step)
    val status = statusText(step.status)
    Column(Modifier.fillMaxWidth().testTag("tool-step-${step.name}")) {
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { expanded = !expanded }.heightIn(min = 40.dp)
            .semantics { contentDescription = "$label, $status" },
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when (step.status) {
                ToolStatus.RUNNING -> CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 1.5.dp)
                ToolStatus.DONE -> AppIcon(Glyph.CHECK, modifier = Modifier.size(16.dp), color = MaterialTheme.colorScheme.primary)
                ToolStatus.WAITING -> AppIcon(Glyph.SHIELD, modifier = Modifier.size(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                ToolStatus.ERROR, ToolStatus.DENIED -> AppIcon(Glyph.ERROR, modifier = Modifier.size(16.dp), color = MaterialTheme.colorScheme.error)
                ToolStatus.STOPPED -> AppIcon(Glyph.STOP, modifier = Modifier.size(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(label, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            step.durationMs?.let { Text(duration(it), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp) }
            AppIcon(Glyph.CHEVRON, modifier = Modifier.size(16.dp).rotate(if (expanded) 180f else 0f), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (expanded) Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(10.dp)) {
            Column(Modifier.padding(10.dp).heightIn(max = 320.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Input", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                Code(input(step))
                if (step.resultPreview.isNotBlank()) {
                    Text(if (step.status == ToolStatus.ERROR || step.status == ToolStatus.DENIED) "Error" else "Result",
                        style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                    Code(output(step))
                }
            }
        }
    }
}

@Composable
private fun Code(text: String) {
    SelectionContainer {
        Text(text, Modifier.horizontalScroll(rememberScrollState()), fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 17.sp,
            color = MaterialTheme.colorScheme.onSurface)
    }
}

private fun arguments(step: TurnStep.ToolCall): JsonObject? = runCatching { Json.parseToJsonElement(step.argsJson).jsonObject }.getOrNull()

/** Human label: what the tool did, never raw JSON. */
fun toolLabel(step: TurnStep.ToolCall): String {
    val args = arguments(step)
    fun arg(key: String) = (args?.get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
    return when (step.name) {
        "create_pdf" -> "Create PDF \u00B7 ${arg("title").ifBlank { arg("file_path") }}"
        "write_file" -> "Create ${arg("file_path")}"
        "write" -> "Write ${arg("file_path")}"
        "read" -> "Read ${arg("file_path")}"
        "present" -> "Present files"
        "run_python" -> "Run Python"
        "pip_install" -> "Install ${(args?.get("packages") as? JsonArray)?.joinToString { (it as? JsonPrimitive)?.contentOrNull.orEmpty() }.orEmpty()}"
        "skill" -> "Load skill ${arg("name")}"
        "web_fetch" -> "Read ${arg("url").substringAfter("://").substringBefore('/')}"
        else -> if ("__" in step.name) "Run ${step.name.substringBefore("__").replace('_', '-')} \u00B7 ${step.name.substringAfter("__")}" else step.name
    }
}

private fun statusText(status: ToolStatus) = when (status) {
    ToolStatus.RUNNING -> "running"
    ToolStatus.WAITING -> "waiting for your approval"
    ToolStatus.DONE -> "done"
    ToolStatus.ERROR -> "failed"
    ToolStatus.DENIED -> "not allowed"
    ToolStatus.STOPPED -> "stopped"
}

private fun input(step: TurnStep.ToolCall): String {
    val args = arguments(step) ?: return step.argsJson.take(4000)
    (args["code"] as? JsonPrimitive)?.contentOrNull?.let { return it.take(8000) }
    (args["markdown"] as? JsonPrimitive)?.contentOrNull?.let { return "# ${(args["title"] as? JsonPrimitive)?.contentOrNull.orEmpty()}\n\n${it.take(4000)}" }
    return args.entries.joinToString("\n") { (key, value) -> "$key: ${(value as? JsonPrimitive)?.contentOrNull ?: value.toString()}".take(2000) }
}

/** run_python results are a JSON envelope; show the streams instead of the envelope. */
private fun output(step: TurnStep.ToolCall): String {
    if (step.name != "run_python" && "__" !in step.name) return step.resultPreview
    val json = runCatching { Json.parseToJsonElement(step.resultPreview).jsonObject }.getOrNull() ?: return step.resultPreview
    fun text(key: String) = (json[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
    return buildString {
        append(text("stdout"))
        if (text("stderr").isNotBlank()) append("\n").append(text("stderr"))
        if (text("error").isNotBlank() && !text("stderr").contains(text("error"))) append("\n").append(text("error"))
        if ((json["timed_out"] as? JsonPrimitive)?.booleanOrNull == true) append("\n[Timed out]")
        (json["files"] as? JsonArray)?.takeIf { it.isNotEmpty() }?.let { files -> append("\nFiles: ").append(files.joinToString { (it as? JsonPrimitive)?.contentOrNull.orEmpty() }) }
    }.trim().ifEmpty { "(no output)" }
}

private fun duration(ms: Long) = if (ms < 1000) "${ms} ms" else "%.1f s".format(ms / 1000.0)

private sealed interface CardPreview {
    data class Picture(val bitmap: Bitmap, val detail: String) : CardPreview
    data class Snippet(val text: String) : CardPreview
    data object None : CardPreview
}

private fun loadPreview(file: File, artifact: Artifact): CardPreview = try {
    when {
        !file.isFile -> CardPreview.None
        artifact.mimeType == "application/pdf" -> ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                renderer.openPage(0).use { page ->
                    val width = 240
                    val height = (width * page.height.toFloat() / page.width.coerceAtLeast(1)).toInt().coerceIn(1, 480)
                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    bitmap.eraseColor(AndroidColor.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    CardPreview.Picture(bitmap, "${renderer.pageCount} page${if (renderer.pageCount == 1) "" else "s"}")
                }
            }
        }
        artifact.mimeType.startsWith("image/") && artifact.mimeType != "image/svg+xml" -> {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 480) sample *= 2
            BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
                ?.let { CardPreview.Picture(it, "${bounds.outWidth} \u00D7 ${bounds.outHeight}") } ?: CardPreview.None
        }
        artifact.fileName.endsWith(".docx", ignoreCase = true) -> CardPreview.Snippet(file.inputStream().use { DocxTextExtractor.extract(it, 600) })
        artifact.mimeType.startsWith("text/") || artifact.mimeType == "application/json" || artifact.mimeType == "image/svg+xml" ->
            CardPreview.Snippet(file.inputStream().use { input -> head(input, 1200) })
        else -> CardPreview.None
    }
} catch (_: Exception) { CardPreview.None }

/** First [limit] bytes as text; InputStream.readNBytes needs API 33. */
private fun head(input: java.io.InputStream, limit: Int): String {
    val buffer = ByteArray(limit)
    var total = 0
    while (total < limit) {
        val count = input.read(buffer, total, limit - total)
        if (count < 0) break
        total += count
    }
    return String(buffer, 0, total, Charsets.UTF_8)
}

private fun size(bytes: Long) = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
}

/** A generated file: preview (PDF first page, image thumbnail or text), then Open, Edit (text files), Save and Share. */
@Composable
fun ArtifactCard(artifact: Artifact, file: File?, onOpen: () -> Unit, onSave: () -> Unit, onShare: () -> Unit, onEdit: (() -> Unit)? = null) {
    val previewKey = file?.lastModified()
    var preview by remember(artifact.path, previewKey) { mutableStateOf<CardPreview?>(null) }
    LaunchedEffect(artifact.path, previewKey) {
        val loaded = try { withContext(Dispatchers.IO) { file?.let { loadPreview(it, artifact) } ?: CardPreview.None } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { CardPreview.None }
        preview = loaded
    }
    val bytes = remember(artifact.path, file?.lastModified()) { file?.takeIf { it.isFile }?.length() ?: 0L }
    Surface(Modifier.fillMaxWidth().testTag("artifact-card-${artifact.fileName}"), shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface, border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column {
            Row(Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(width = 56.dp, height = 72.dp).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center) {
                    when (val loaded = preview) {
                        is CardPreview.Picture -> Image(loaded.bitmap.asImageBitmap(), "Preview of ${artifact.fileName}", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        null -> CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 1.5.dp)
                        else -> AppIcon(Glyph.DOCUMENT, modifier = Modifier.size(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(artifact.fileName, fontWeight = FontWeight.Medium, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    val detail = (preview as? CardPreview.Picture)?.detail
                    Text(listOfNotNull(artifact.fileName.substringAfterLast('.', "file").uppercase(), detail, size(bytes).takeIf { bytes > 0 }).joinToString(" \u00B7 "),
                        color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                    (preview as? CardPreview.Snippet)?.let { snippet ->
                        Text(snippet.text.trim(), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, lineHeight = 15.sp,
                            maxLines = 4, overflow = TextOverflow.Ellipsis, fontFamily = FontFamily.Monospace)
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .6f), thickness = 0.5.dp)
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                TextButton(onClick = onOpen, modifier = Modifier.semantics { contentDescription = "Open ${artifact.fileName}" }) { Text("Open") }
                if (onEdit != null) TextButton(onClick = onEdit, modifier = Modifier.semantics { contentDescription = "Edit ${artifact.fileName}" }) { Text("Edit") }
                TextButton(onClick = onSave, modifier = Modifier.semantics { contentDescription = "Save ${artifact.fileName} to Downloads" }) { Text("Save") }
                TextButton(onClick = onShare, modifier = Modifier.semantics { contentDescription = "Share ${artifact.fileName}" }) { Text("Share") }
            }
        }
    }
}
