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
import app.dsandroid.core.markdown.*

@Composable
fun MarkdownText(text: String, modifier: Modifier = Modifier) {
    val blocks = remember(text) { MarkdownParser.parse(text) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        blocks.forEach { block ->
            when (block) {
                is MarkdownBlock.Heading -> InlineText(block.text, style = when (block.level) {
                    1 -> MaterialTheme.typography.headlineSmall
                    2 -> MaterialTheme.typography.titleLarge
                    else -> MaterialTheme.typography.titleMedium
                }.copy(fontWeight = FontWeight.Bold))
                is MarkdownBlock.Paragraph -> InlineText(block.text)
                is MarkdownBlock.Code -> CodeBlock(block)
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

@Suppress("DEPRECATION")
@Composable
private fun InlineText(text: String, modifier: Modifier = Modifier, style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.bodyLarge) {
    val uriHandler = LocalUriHandler.current
    val linkColor = MaterialTheme.colorScheme.primary
    val annotated = remember(text, linkColor) {
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
                )) { append(part.text) }
                if (link != null) pop()
            }
        }
    }
    SelectionContainer {
        ClickableText(annotated, modifier, style = style.copy(color = if (style.color == androidx.compose.ui.graphics.Color.Unspecified) MaterialTheme.colorScheme.onSurface else style.color),
            onClick = { offset -> annotated.getStringAnnotations("url", offset, offset).firstOrNull()?.let { runCatching { uriHandler.openUri(it.item) } } })
    }
}

@Suppress("DEPRECATION")
@Composable
private fun CodeBlock(block: MarkdownBlock.Code) {
    val clipboard = LocalClipboardManager.current
    var copied by remember(block.text) { mutableStateOf(false) }
    Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.medium) {
        Column(Modifier.padding(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(block.language.ifBlank { stringResource(R.string.code) }, style = MaterialTheme.typography.labelMedium)
                TextButton(onClick = { clipboard.setText(AnnotatedString(block.text)); copied = true }) { Text(if (copied) stringResource(R.string.copied) else stringResource(R.string.copy)) }
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
