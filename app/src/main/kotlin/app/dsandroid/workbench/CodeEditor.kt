package app.dsandroid.workbench

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.dsandroid.core.editor.EditHistory
import app.dsandroid.core.editor.EditorLanguage
import app.dsandroid.core.editor.EditorSnapshot
import app.dsandroid.core.editor.SyntaxTokenizer
import app.dsandroid.core.editor.TextSearch

/** Editor text, undo/redo and find for one buffer. [onTextChanged] lets the owner keep drafts across recreation. */
@Stable
class EditorState(initial: String, val language: EditorLanguage, private val onTextChanged: (String) -> Unit = {}) {
    var value by mutableStateOf(TextFieldValue(initial))
        private set
    private val history = EditHistory(EditorSnapshot(initial, 0, 0))
    var canUndo by mutableStateOf(false)
        private set
    var canRedo by mutableStateOf(false)
        private set
    var findOpen by mutableStateOf(false)
    var query by mutableStateOf("")
        private set
    var matchIndex by mutableIntStateOf(-1)
        private set
    val text: String get() = value.text

    fun update(next: TextFieldValue, now: Long = System.currentTimeMillis()) {
        val changed = next.text != value.text
        history.record(EditorSnapshot(next.text, next.selection.start, next.selection.end), now)
        value = next
        refresh()
        if (changed) { matchIndex = -1; onTextChanged(next.text) }
    }

    fun undo() { history.undo()?.let(::restore) }
    fun redo() { history.redo()?.let(::restore) }

    /** Replaces the whole buffer (another version was chosen); history starts again. */
    fun reset(text: String) {
        history.reset(EditorSnapshot(text, 0, 0))
        value = TextFieldValue(text)
        matchIndex = -1
        refresh()
    }

    fun search(query: String) {
        this.query = query
        val matches = matches()
        matchIndex = TextSearch.next(matches, value.selection.min)
        select(matches)
    }
    fun findNext() { val matches = matches(); matchIndex = TextSearch.next(matches, value.selection.max); select(matches) }
    fun findPrevious() { val matches = matches(); matchIndex = TextSearch.previous(matches, value.selection.min); select(matches) }
    fun matches(): List<IntRange> = if (findOpen) TextSearch.find(value.text, query) else emptyList()

    private fun select(matches: List<IntRange>) {
        val match = matches.getOrNull(matchIndex) ?: return
        value = value.copy(selection = TextRange(match.first, match.last + 1))
    }

    private fun restore(snapshot: EditorSnapshot) {
        val end = snapshot.text.length
        value = TextFieldValue(snapshot.text, TextRange(snapshot.selectionStart.coerceIn(0, end), snapshot.selectionEnd.coerceIn(0, end)))
        matchIndex = -1
        refresh()
        onTextChanged(snapshot.text)
    }

    private fun refresh() { canUndo = history.canUndo; canRedo = history.canRedo }
}

/** Syntax colors and find highlights; offsets are unchanged so the caret maps one to one. */
private class SyntaxTransformation(
    private val language: EditorLanguage, private val palette: SyntaxPalette, private val matches: List<IntRange>, private val current: Int,
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val raw = text.text
        val styled = buildAnnotatedString {
            append(raw)
            SyntaxTokenizer.tokenize(raw, language).forEach { addStyle(palette.style(it.type), it.start, it.end) }
            matches.forEachIndexed { index, range ->
                if (range.last < raw.length) addStyle(SpanStyle(background = if (index == current) palette.currentMatch else palette.match), range.first, range.last + 1)
            }
        }
        return TransformedText(styled, OffsetMapping.Identity)
    }
    override fun equals(other: Any?) = other is SyntaxTransformation && other.language == language && other.palette == palette &&
        other.matches == matches && other.current == current
    override fun hashCode() = listOf(language, palette, matches, current).hashCode()
}

/**
 * Monospace editor with line numbers, syntax colors and find highlights. Lines never wrap (the text scrolls
 * sideways), so each number stays level with its line. [textScale] is the user's text size on top of system scaling.
 */
@Composable
fun CodeEditor(state: EditorState, textScale: Float, modifier: Modifier = Modifier, readOnly: Boolean = false) {
    val dark = MaterialTheme.colorScheme.surface.luminance() < .5f
    val palette = if (dark) SyntaxPalette.Dark else SyntaxPalette.Light
    val scale = textScale.coerceIn(.85f, 1.6f)
    val style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = (13 * scale).sp, lineHeight = (20 * scale).sp, color = palette.plain)
    val matches = remember(state.value.text, state.query, state.findOpen) { state.matches() }
    val transformation = remember(state.language, palette, matches, state.matchIndex) { SyntaxTransformation(state.language, palette, matches, state.matchIndex) }
    val lines = TextSearch.lineCount(state.value.text)
    val digits = lines.toString().length.coerceAtLeast(2)
    val gutter: Dp = with(LocalDensity.current) { (style.fontSize.toPx() * .62f * digits).toDp() } + 16.dp
    val vertical = rememberScrollState()
    val horizontal = rememberScrollState()
    BoxWithConstraints(modifier.background(palette.background)) {
        val viewportWidth = maxWidth
        val viewportHeight = if (maxHeight == Dp.Infinity) 0.dp else maxHeight
        Row(Modifier.fillMaxSize().verticalScroll(vertical)) {
            Text((1..lines).joinToString("\n"), Modifier.width(gutter).padding(top = 8.dp, end = 8.dp).clearAndSetSemantics { },
                style = style.copy(color = palette.gutter, textAlign = TextAlign.End), softWrap = false)
            Box(Modifier.weight(1f).horizontalScroll(horizontal)) {
                BasicTextField(
                    value = state.value, onValueChange = { state.update(it) }, readOnly = readOnly,
                    textStyle = style, cursorBrush = SolidColor(MaterialTheme.colorScheme.primary), visualTransformation = transformation,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrect = false),
                    modifier = Modifier.widthIn(min = (viewportWidth - gutter).coerceAtLeast(48.dp)).heightIn(min = viewportHeight)
                        .padding(top = 8.dp, end = 24.dp, bottom = 48.dp).testTag("code-editor")
                        .semantics { contentDescription = "Code editor, ${state.language.label}" },
                )
            }
        }
    }
}

/** Find bar: query, "2 of 5", previous and next. */
@Composable
fun FindBar(state: EditorState, onClose: () -> Unit) {
    val count = state.matches().size
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(state.query, { state.search(it.take(200)) }, singleLine = true, label = { Text("Find") },
            modifier = Modifier.weight(1f).testTag("editor-find"))
        Text(TextSearch.label(state.matchIndex, count), Modifier.padding(horizontal = 8.dp), style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = state::findPrevious, enabled = count > 0) { Text("Prev") }
        TextButton(onClick = state::findNext, enabled = count > 0) { Text("Next") }
        TextButton(onClick = onClose) { Text("Done") }
    }
}
