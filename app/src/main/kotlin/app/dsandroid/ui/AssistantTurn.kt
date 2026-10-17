package app.dsandroid.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.dsandroid.core.agent.ToolStatus
import app.dsandroid.core.agent.TurnStep
import app.dsandroid.core.llm.LlmJson
import app.dsandroid.core.markdown.MarkdownParser
import app.dsandroid.core.tools.Artifact
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssistantTurn(item: ChatItem.Assistant, working: Boolean, openArtifact: (Artifact) -> Unit) {
    var detailsIndex by remember(item.id) { mutableStateOf<Int?>(null) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item.steps.forEachIndexed { index, step ->
            when (step) {
                is TurnStep.Reasoning -> {
                    var expanded by rememberSaveable(item.id, index, step.complete) { mutableStateOf(false) }
                    TimelineRow {
                        Column(Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(if (step.complete) "Thought for ${maxOf(1L, (step.durationMs + 999) / 1000)} seconds" else "Thinking",
                                    Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelLarge)
                                Text(if (expanded) "⌃" else if (step.complete) "›" else "⌄", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Spacer(Modifier.height(4.dp))
                            if (expanded) CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant) {
                                SelectionContainer { Text(MarkdownParser.inline(step.text).joinToString("") { it.text }, style = MaterialTheme.typography.bodySmall) }
                            } else Text(MarkdownParser.inline(step.text.lineSequence().firstOrNull().orEmpty()).joinToString("") { it.text },
                                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                is TurnStep.ToolCall -> TimelineRow {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            AppIcon(Glyph.TOOL, modifier = Modifier.size(16.dp), color = MaterialTheme.colorScheme.primary)
                            Text(toolLabel(step), Modifier.weight(1f), color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.labelLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            TextButton(onClick = { detailsIndex = index }, contentPadding = PaddingValues(horizontal = 4.dp)) { Text("Details", style = MaterialTheme.typography.labelMedium) }
                            ToolStatusIcon(step.status)
                        }
                        if (step.status in listOf(ToolStatus.WAITING, ToolStatus.DENIED, ToolStatus.ERROR, ToolStatus.STOPPED)) {
                            Text(statusLabel(step.status), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                is TurnStep.Answer -> MarkdownText(step.text, Modifier.fillMaxWidth().padding(vertical = 6.dp))
            }
        }
        if (working && item.steps.lastOrNull() !is TurnStep.Answer) TypingIndicator()
        item.artifacts.forEach { artifact ->
            OutlinedCard(onClick = { openArtifact(artifact) }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    AppIcon(Glyph.DOCUMENT, color = MaterialTheme.colorScheme.primary)
                    Column(Modifier.weight(1f)) {
                        Text(artifact.fileName, style = MaterialTheme.typography.titleSmall)
                        Text("Tap to preview · Share or save", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text("›")
                }
            }
        }
    }
    val detail = detailsIndex?.let { item.steps.getOrNull(it) as? TurnStep.ToolCall }
    if (detail != null) ModalBottomSheet(onDismissRequest = { detailsIndex = null }) { ToolDetails(detail) }
}

@Composable
private fun TimelineRow(content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        VerticalDivider(Modifier.fillMaxHeight(), color = MaterialTheme.colorScheme.outlineVariant, thickness = 2.dp)
        Surface(Modifier.weight(1f), color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(12.dp), content = content)
    }
}

private fun toolLabel(step: TurnStep.ToolCall): String = when {
    step.status == ToolStatus.DONE && step.name.startsWith("create_") -> step.resultPreview.lineSequence().firstOrNull().orEmpty()
    step.name == "web_fetch" -> if (step.status == ToolStatus.DONE) "Read a web page" else "Searching the web"
    step.name == "read_file" -> "Read a file"
    step.name == "list_files" -> "Browse files"
    step.name == "create_pdf" -> "Create a PDF"
    step.name == "create_svg" -> "Create an SVG"
    step.name == "create_csv" -> "Create a CSV"
    step.name == "create_markdown" -> "Create a Markdown file"
    else -> step.name.replace('_', ' ')
}

private fun statusLabel(status: ToolStatus): String = when (status) {
    ToolStatus.RUNNING -> "Running"
    ToolStatus.WAITING -> "Waiting for you"
    ToolStatus.DONE -> "Done"
    ToolStatus.ERROR -> "Failed"
    ToolStatus.DENIED -> "Denied"
    ToolStatus.STOPPED -> "Stopped"
}

@Composable
private fun ToolStatusIcon(status: ToolStatus) {
    if (status == ToolStatus.RUNNING) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
    else AppIcon(when (status) { ToolStatus.DONE -> Glyph.CHECK; ToolStatus.WAITING -> Glyph.SHIELD; else -> Glyph.CLOSE },
        statusLabel(status), Modifier.size(16.dp), if (status == ToolStatus.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun TypingIndicator() {
    val transition = rememberInfiniteTransition(label = "Working")
    Row(Modifier.padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        repeat(3) { index ->
            val alpha by transition.animateFloat(0.25f, 1f, infiniteRepeatable(tween(500, delayMillis = index * 150), RepeatMode.Reverse), label = "Dot $index")
            Box(Modifier.size(6.dp).alpha(alpha).background(MaterialTheme.colorScheme.onSurfaceVariant, CircleShape))
        }
    }
}

@Suppress("DEPRECATION")
@Composable
private fun ToolDetails(step: TurnStep.ToolCall) {
    val clipboard = LocalClipboardManager.current
    val arguments = remember(step.argsJson) {
        runCatching { Json(LlmJson) { prettyPrint = true }.encodeToString(JsonElement.serializer(), LlmJson.parseToJsonElement(step.argsJson)) }.getOrDefault(step.argsJson)
    }
    Column(Modifier.fillMaxWidth().fillMaxHeight(0.85f).verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(step.name, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleLarge)
        Text(statusLabel(step.status) + (step.durationMs?.let { " · $it ms" } ?: ""))
        Text("Arguments", style = MaterialTheme.typography.titleSmall)
        SelectionContainer { Text(arguments, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
        Text("Result", style = MaterialTheme.typography.titleSmall)
        TextButton(onClick = { clipboard.setText(AnnotatedString(step.resultPreview)) }, enabled = step.resultPreview.isNotEmpty()) { Text("Copy result") }
        SelectionContainer { Text(step.resultPreview.ifBlank { "No result yet." }, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
        Spacer(Modifier.height(24.dp))
    }
}
