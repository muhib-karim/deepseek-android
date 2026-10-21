package app.dsandroid.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import app.dsandroid.R
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.dsandroid.core.agent.ToolStatus
import app.dsandroid.core.agent.TurnStep
import app.dsandroid.core.llm.LlmJson
import app.dsandroid.core.markdown.MarkdownParser
import app.dsandroid.core.tools.Artifact
import app.dsandroid.core.artifacts.FencedArtifact
import app.dsandroid.core.artifacts.FencedArtifacts
import app.dsandroid.core.guard.WorkspacePath

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssistantTurn(item: ChatItem.Assistant, working: Boolean, openArtifact: (Artifact) -> Unit, shareArtifact: (Artifact) -> Unit = {},
    saveArtifact: (Artifact) -> Unit = {}, collapseThinking: Boolean = true,
    workspace: WorkspacePath, openFence: (Int, FencedArtifact) -> Unit,
    speech: app.dsandroid.voice.SpeechPlayer? = null, speechError: (String) -> Unit = {}) {
    var sourcesVisible by remember(item.id) { mutableStateOf(false) }
    var selectedSource by remember(item.id) { mutableStateOf<Int?>(null) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item.steps.forEachIndexed { index, step ->
            when (step) {
                is TurnStep.Reasoning -> {
                    var expanded by rememberSaveable(item.id, index, step.complete, collapseThinking) { mutableStateOf(!collapseThinking) }
                    TimelineRow {
                        Column(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { expanded = !expanded }.padding(10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(if (step.complete) stringResource(R.string.thought_seconds, maxOf(1L, (step.durationMs + 999) / 1000)) else stringResource(R.string.thinking),
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
                // The same collapsible card as the account route: icon, title, status; tap for arguments and result.
                is TurnStep.ToolCall -> app.dsandroid.deepseek.ToolStepRow(step, title = toolLabel(step),
                    details = if (step.name == "code_interpreter") ({ InterpreterCardContent(step) }) else null)
                is TurnStep.Answer -> Column {
                    val answer = app.dsandroid.core.chat.FollowUps.display(step.text, working)
                    val speechId = "${item.id}:$index"
                    if (speech != null && speech.owner == speechId) SpokenText(speech.text, speech, Modifier.fillMaxWidth().padding(vertical = 6.dp))
                    else MarkdownText(answer, Modifier.fillMaxWidth().padding(vertical = 6.dp), item.sources, onCitation = {
                        selectedSource = it; sourcesVisible = true
                    })
                    if (!working && speech != null) ReadAloudControls(speechId, answer, speech, speechError)
                    val fences = remember(step.text) { FencedArtifacts.parse(step.text) }
                    fences.forEachIndexed { fenceIndex, fence ->
                        OutlinedCard(onClick = { openFence(index * 100 + fenceIndex, fence) }, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Column(Modifier.padding(16.dp)) {
                                Text(stringResource(R.string.canvas_fenced_title, fence.format.uppercase(java.util.Locale.ROOT)), style = MaterialTheme.typography.titleSmall)
                                Text(stringResource(R.string.tap_preview), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
        if (working && item.steps.lastOrNull() !is TurnStep.Answer) TypingIndicator()
        if (item.sources.isNotEmpty()) TextButton(onClick = { selectedSource = null; sourcesVisible = true }) {
            Text(stringResource(R.string.sources_count, item.sources.size))
        }
        item.artifacts.forEach { artifact ->
            OutlinedCard(onClick = { openArtifact(artifact) }, modifier = Modifier.fillMaxWidth().testTag("artifact-card-${artifact.fileName}"), shape = RoundedCornerShape(16.dp)) {
                if (artifact.mimeType == "image/svg+xml") InlineSvgArtifact(artifact, workspace) { openArtifact(artifact) }
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    AppIcon(Glyph.DOCUMENT, color = MaterialTheme.colorScheme.primary)
                    Column(Modifier.weight(1f)) {
                        Text(artifact.fileName, style = MaterialTheme.typography.titleSmall)
                        Text(stringResource(R.string.tap_preview), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                val open = stringResource(R.string.library_open)
                val share = stringResource(R.string.viewer_share)
                val download = stringResource(R.string.save_to_downloads)
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp)) {
                    TextButton(onClick = { openArtifact(artifact) }, modifier = Modifier.semantics { contentDescription = "$open ${artifact.fileName}" }) { Text(open) }
                    TextButton(onClick = { saveArtifact(artifact) }, modifier = Modifier.semantics { contentDescription = "$download: ${artifact.fileName}" }) { Text(download) }
                    TextButton(onClick = { shareArtifact(artifact) }, modifier = Modifier.semantics { contentDescription = "$share ${artifact.fileName}" }) { Text(share) }
                }
            }
        }
    }
    if (sourcesVisible) SourcesSheet(item.sources, selectedSource) { sourcesVisible = false }
}

@Composable
private fun TimelineRow(content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        VerticalDivider(Modifier.fillMaxHeight(), color = MaterialTheme.colorScheme.outlineVariant, thickness = 2.dp)
        Surface(Modifier.weight(1f), color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(12.dp), content = content)
    }
}

@Composable
private fun toolLabel(step: TurnStep.ToolCall): String {
    val args = remember(step.argsJson) { runCatching { LlmJson.parseToJsonElement(step.argsJson) as? kotlinx.serialization.json.JsonObject }.getOrNull() }
    fun arg(key: String): String = (args?.get(key) as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
    val resultName = if (step.status == ToolStatus.DONE) Regex("^Created (\\S+)").find(step.resultPreview.lineSequence().first())?.groupValues?.get(1).orEmpty() else ""
    val done = step.status == ToolStatus.DONE
    val zippedCount = if(done) Regex("^Zipped (\\d+) files").find(step.resultPreview)?.groupValues?.get(1)?.toIntOrNull() else null
    phoneActionLabel(step.name)?.let { return stringResource(it) }
    return when {
        step.name == "code_interpreter" -> if (!done) stringResource(R.string.interpreter_running)
            else stringResource(if (arg("language").equals("python", ignoreCase = true)) R.string.ran_python else R.string.ran_javascript)
        step.name == "render_chart" -> stringResource(if (done) R.string.chart_rendered else R.string.chart_rendering)
        step.name == "web_search" -> stringResource(if(done) R.string.searched_query else R.string.searching_query, arg("query"))
        step.name in listOf("web_fetch", "visit_web") -> stringResource(if(done) R.string.visited_site else R.string.visiting_site, runCatching { java.net.URI(arg("url")).host }.getOrNull().orEmpty())
        step.name == "zip_files" -> if(zippedCount != null) stringResource(R.string.zipped_files, zippedCount) else stringResource(if(done) R.string.zipped_archive else R.string.zipping_files)
        step.name.startsWith("create_") -> listOf(resultName, arg("file_path"), arg("path"), arg("fileName")).firstOrNull { it.isNotBlank() }?.let { stringResource(if(done) R.string.created_file else R.string.creating_file, it.substringAfterLast('/')) } ?: stringResource(if(done) R.string.created_artifact else R.string.creating_artifact)
        step.name == "read_file" -> stringResource(R.string.read_file)
        step.name in listOf("list_files", "list_dir", "list_zip") -> stringResource(R.string.browse_files)
        else -> step.name.replace('_', ' ')
    }
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
