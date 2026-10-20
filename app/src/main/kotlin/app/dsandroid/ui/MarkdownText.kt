package app.dsandroid.ui

import androidx.compose.ui.res.stringResource
import app.dsandroid.R

import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
import app.dsandroid.core.editor.EditorLanguage
import app.dsandroid.core.markdown.*
import app.dsandroid.core.tools.WebSource
import app.dsandroid.core.workbench.ArtifactKind

private data class CitationLinks(val numbers: Set<Int> = emptySet(), val open: (Int) -> Unit = {})
private val LocalCitations = compositionLocalOf { CitationLinks() }

/**
 * [onRunCode], when set, adds a Run action to Python code blocks. [onPreviewCode] adds Preview to HTML, SVG and Markdown
 * blocks (artifacts panel) and [onEditCode] adds Edit to blocks in other editor languages; both receive (language, code).
 */
@Composable
fun MarkdownText(text: String, modifier: Modifier = Modifier, sources: List<WebSource> = emptyList(), onCitation: (Int) -> Unit = {},
    onRunCode: ((String) -> Unit)? = null, onPreviewCode: ((String, String) -> Unit)? = null, onEditCode: ((String, String) -> Unit)? = null) {
    val blocks = remember(text) { MarkdownParser.parse(text) }
    CompositionLocalProvider(LocalCitations provides CitationLinks(sources.map { it.number }.toSet(), onCitation)) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        blocks.forEach { block ->
            when (block) {
                is MarkdownBlock.Heading -> InlineText(block.text, style = when (block.level) {
                    1 -> MaterialTheme.typography.headlineSmall
                    2 -> MaterialTheme.typography.titleLarge
                    else -> MaterialTheme.typography.titleMedium
                }.copy(fontWeight = FontWeight.Bold))
                is MarkdownBlock.Paragraph -> InlineText(block.text)
                is MarkdownBlock.Code -> {
                    val artifact = ArtifactKind.fromFence(block.language, block.text) != null
                    CodeBlock(block, onRunCode?.takeIf { block.language.lowercase() in setOf("python", "py", "python3") },
                        onPreviewCode?.takeIf { artifact }?.let { callback -> { callback(block.language, block.text) } },
                        onEditCode?.takeIf { !artifact && EditorLanguage.forFence(block.language) != null }?.let { callback -> { callback(block.language, block.text) } })
                }
                is MarkdownBlock.ListBlock -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    block.items.forEachIndexed { index, item ->
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(if (block.ordered) "${block.start + index}." else "•")
                            InlineText(item, Modifier.weight(1f))
                        }
                    }
                }
                is MarkdownBlock.Quote -> Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    VerticalDivider(Modifier.fillMaxHeight(), color = MaterialTheme.colorScheme.outlineVariant, thickness = 3.dp)
                    InlineText(block.text, Modifier.weight(1f), MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurfaceVariant))
                }
                is MarkdownBlock.Table -> DataTable(block.header, block.rows, block.alignments, markdown = true)
                MarkdownBlock.Rule -> HorizontalDivider(Modifier.padding(vertical = 8.dp))
            }
        }
    }
    }
}

@Suppress("DEPRECATION")
@Composable
private fun InlineText(text: String, modifier: Modifier = Modifier, style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.bodyLarge) {
    val uriHandler = LocalUriHandler.current
    val linkColor = MaterialTheme.colorScheme.primary
    val citations = LocalCitations.current
    val annotated = remember(text, linkColor, citations.numbers) {
        buildAnnotatedString {
            MarkdownParser.inline(text).forEach { part ->
                val link = part.link
                if (link != null) pushStringAnnotation("url", link)
                val decorations = buildList { if (part.strikethrough) add(TextDecoration.LineThrough); if (link != null) add(TextDecoration.Underline) }
                withStyle(SpanStyle(
                    fontWeight = if (part.bold) FontWeight.Bold else null,
                    fontStyle = if (part.italic) FontStyle.Italic else null,
                    fontFamily = if (part.code) FontFamily.Monospace else null,
                    color = if (link != null) linkColor else androidx.compose.ui.graphics.Color.Unspecified,
                    textDecoration = if (decorations.isEmpty()) null else TextDecoration.combine(decorations),
                )) {
                    val start = length
                    append(part.text)
                    if (link == null && !part.code) Regex("\\[(\\d+)\\]").findAll(part.text).forEach { match ->
                        val number = match.groupValues[1].toIntOrNull()
                        if (number != null && number in citations.numbers) {
                            val from = start + match.range.first
                            val until = start + match.range.last + 1
                            addStringAnnotation("citation", number.toString(), from, until)
                            addStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline), from, until)
                        }
                    }
                }
                if (link != null) pop()
            }
        }
    }
    SelectionContainer {
        ClickableText(annotated, modifier, style = style.copy(color = if (style.color == androidx.compose.ui.graphics.Color.Unspecified) MaterialTheme.colorScheme.onSurface else style.color),
            onClick = { offset ->
                val citation = annotated.getStringAnnotations("citation", offset, offset).firstOrNull()?.item?.toIntOrNull()
                if (citation != null) citations.open(citation)
                else annotated.getStringAnnotations("url", offset, offset).firstOrNull()?.let { runCatching { uriHandler.openUri(it.item) } }
            })
    }
}

@Suppress("DEPRECATION")
@Composable
private fun CodeBlock(block: MarkdownBlock.Code, onRun: ((String) -> Unit)? = null, onPreview: (() -> Unit)? = null, onEdit: (() -> Unit)? = null) {
    val clipboard = LocalClipboardManager.current
    var copied by remember(block.text) { mutableStateOf(false) }
    Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.medium) {
        Column(Modifier.padding(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(block.language.ifBlank { stringResource(R.string.code) }, style = MaterialTheme.typography.labelMedium)
                Row {
                    if (onRun != null) TextButton(onClick = { onRun(block.text) }) { Text(stringResource(R.string.run_code)) }
                    if (onPreview != null) TextButton(onClick = onPreview, modifier = Modifier.testTag("code-preview")) { Text(stringResource(R.string.canvas_preview)) }
                    if (onEdit != null) TextButton(onClick = onEdit, modifier = Modifier.testTag("code-edit")) { Text(stringResource(R.string.code_edit)) }
                    TextButton(onClick = { clipboard.setText(AnnotatedString(block.text)); copied = true }) { Text(if (copied) stringResource(R.string.copied) else stringResource(R.string.copy)) }
                }
            }
            SelectionContainer {
                Text(block.text, Modifier.horizontalScroll(rememberScrollState()).padding(bottom = 8.dp), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
fun DataTable(header: List<String>, rows: List<List<String>>, alignments: List<TableAlignment> = emptyList(), markdown: Boolean = false) {
    Column {
        Column(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            (listOf(header) + rows.take(500)).forEachIndexed { rowIndex, row ->
                Row(Modifier.height(IntrinsicSize.Min)) {
                    header.indices.forEach { column ->
                        Surface(Modifier.width(168.dp).fillMaxHeight().border(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
                            color = if (rowIndex == 0) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface) {
                            val style = MaterialTheme.typography.bodyMedium.copy(fontWeight = if (rowIndex == 0) FontWeight.Bold else FontWeight.Normal,
                                textAlign = when (alignments.getOrNull(column)) { TableAlignment.RIGHT -> TextAlign.End; TableAlignment.CENTER -> TextAlign.Center; else -> TextAlign.Start })
                            if (markdown) InlineText(row.getOrElse(column) { "" }, Modifier.padding(10.dp), style)
                            else SelectionContainer { Text(row.getOrElse(column) { "" }, Modifier.padding(10.dp), style = style) }
                        }
                    }
                }
            }
        }
        if (rows.size > 500) Text(stringResource(R.string.table_preview_limit), style = MaterialTheme.typography.bodySmall)
    }
}
