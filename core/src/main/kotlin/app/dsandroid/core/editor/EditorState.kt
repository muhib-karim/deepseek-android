package app.dsandroid.core.editor

/** Editor text with its selection; a collapsed selection is the caret. */
data class EditorSnapshot(val text: String, val selectionStart: Int, val selectionEnd: Int)

/**
 * Undo and redo for the editor. Single-character typing (or deleting) within [mergeWindowMillis] of the previous
 * keystroke joins one step; newlines, pastes and switching between typing and deleting start a new step.
 * Selection-only changes never create steps.
 */
class EditHistory(initial: EditorSnapshot, private val maxSteps: Int = 200, private val mergeWindowMillis: Long = 1000) {
    private enum class Kind { INSERT, DELETE, OTHER }

    private val undoSteps = ArrayDeque<EditorSnapshot>()
    private val redoSteps = ArrayDeque<EditorSnapshot>()
    private var lastKind = Kind.OTHER
    private var lastEditAt = 0L

    var current: EditorSnapshot = initial
        private set
    val canUndo: Boolean get() = undoSteps.isNotEmpty()
    val canRedo: Boolean get() = redoSteps.isNotEmpty()

    init { require(maxSteps >= 1 && mergeWindowMillis >= 0) }

    fun record(next: EditorSnapshot, now: Long) {
        if (next.text == current.text) { current = next; return }
        val kind = classify(current.text, next.text)
        val merge = kind != Kind.OTHER && kind == lastKind && undoSteps.isNotEmpty() && now - lastEditAt in 0..mergeWindowMillis
        if (!merge) {
            undoSteps.addLast(current)
            while (undoSteps.size > maxSteps) undoSteps.removeFirst()
        }
        redoSteps.clear()
        current = next
        lastKind = kind
        lastEditAt = now
    }

    fun undo(): EditorSnapshot? {
        val previous = undoSteps.removeLastOrNull() ?: return null
        redoSteps.addLast(current)
        current = previous
        lastKind = Kind.OTHER
        return previous
    }

    fun redo(): EditorSnapshot? {
        val next = redoSteps.removeLastOrNull() ?: return null
        undoSteps.addLast(current)
        current = next
        lastKind = Kind.OTHER
        return next
    }

    fun reset(snapshot: EditorSnapshot) {
        undoSteps.clear(); redoSteps.clear()
        current = snapshot
        lastKind = Kind.OTHER
    }

    private fun classify(old: String, new: String): Kind {
        var prefix = 0
        while (prefix < old.length && prefix < new.length && old[prefix] == new[prefix]) prefix++
        var suffix = 0
        while (suffix < old.length - prefix && suffix < new.length - prefix && old[old.length - 1 - suffix] == new[new.length - 1 - suffix]) suffix++
        val removed = old.length - prefix - suffix
        val inserted = new.length - prefix - suffix
        return when {
            removed == 0 && inserted == 1 -> if (new[prefix] == '\n') Kind.OTHER else Kind.INSERT
            inserted == 0 && removed == 1 -> if (old[prefix] == '\n') Kind.OTHER else Kind.DELETE
            else -> Kind.OTHER
        }
    }
}

/** Find in the editor: non-overlapping matches, wrap-around navigation and the "2 of 5" label. */
object TextSearch {
    fun find(text: String, query: String, ignoreCase: Boolean = true, limit: Int = 10_000): List<IntRange> {
        if (query.isEmpty()) return emptyList()
        val matches = ArrayList<IntRange>()
        var from = 0
        while (matches.size < limit) {
            val index = text.indexOf(query, from, ignoreCase)
            if (index < 0) break
            matches += index until index + query.length
            from = index + query.length
        }
        return matches
    }

    /** The first match starting at or after [caret], wrapping to the first; -1 when there are none. */
    fun next(matches: List<IntRange>, caret: Int): Int {
        if (matches.isEmpty()) return -1
        val index = matches.indexOfFirst { it.first >= caret }
        return if (index < 0) 0 else index
    }

    /** The last match starting before [caret], wrapping to the last; -1 when there are none. */
    fun previous(matches: List<IntRange>, caret: Int): Int {
        if (matches.isEmpty()) return -1
        val index = matches.indexOfLast { it.first < caret }
        return if (index < 0) matches.lastIndex else index
    }

    fun lineCount(text: String): Int = text.count { it == '\n' } + 1

    fun label(index: Int, count: Int): String = if (count == 0 || index < 0) "No results" else "${index + 1} of $count"
}
