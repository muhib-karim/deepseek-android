package app.dsandroid.core.editor

import org.junit.Assert.*
import org.junit.Test

class EditorStateTest {
    private fun at(text: String, caret: Int = text.length) = EditorSnapshot(text, caret, caret)

    @Test fun quickTypingIsOneUndoStepAndPausesSplitSteps() {
        val history = EditHistory(at(""))
        history.record(at("a"), now = 0)
        history.record(at("ab"), now = 300)
        history.record(at("abc"), now = 600)
        history.record(at("abcd"), now = 3000)
        assertTrue(history.canUndo)
        assertEquals("abc", history.undo()!!.text)
        assertEquals("", history.undo()!!.text)
        assertNull(history.undo())
        assertFalse(history.canUndo)
        assertEquals("abc", history.redo()!!.text)
        assertEquals("abcd", history.redo()!!.text)
        assertNull(history.redo())
    }

    @Test fun newlinesPastesAndDirectionChangesStartNewSteps() {
        val history = EditHistory(at("x"))
        history.record(at("xy"), now = 0)
        history.record(at("xy\n"), now = 100)
        history.record(at("xy\nz"), now = 200)
        history.record(at("xy\nz pasted text"), now = 300)
        history.record(at("xy\nz pasted tex"), now = 400)
        assertEquals("xy\nz pasted text", history.undo()!!.text)
        assertEquals("xy\nz", history.undo()!!.text)
        assertEquals("xy\n", history.undo()!!.text)
        assertEquals("xy", history.undo()!!.text)
        assertEquals("x", history.undo()!!.text)
    }

    @Test fun anEditAfterUndoClearsRedoAndSelectionChangesAreNotSteps() {
        val history = EditHistory(at("one"))
        history.record(at("one two"), now = 0)
        history.record(EditorSnapshot("one two", 0, 3), now = 50)
        assertEquals(EditorSnapshot("one two", 0, 3), history.current)
        assertEquals("one", history.undo()!!.text)
        history.record(at("one!"), now = 5000)
        assertFalse(history.canRedo)
        assertEquals("one", history.undo()!!.text)
        history.reset(at("fresh"))
        assertFalse(history.canUndo || history.canRedo)
        assertEquals("fresh", history.current.text)
    }

    @Test fun historyIsBounded() {
        val history = EditHistory(at(""), maxSteps = 5)
        var text = ""
        repeat(20) { index -> text += "\n$index"; history.record(at(text), now = index * 10_000L) }
        var undone = 0
        while (history.undo() != null) undone++
        assertEquals(5, undone)
    }

    @Test fun findReturnsNonOverlappingMatchesAndWrapsAroundTheCaret() {
        val text = "Alpha beta ALPHA alphabet aaaa"
        val matches = TextSearch.find(text, "alpha")
        assertEquals(listOf(0 until 5, 11 until 16, 17 until 22), matches)
        assertEquals(listOf(0 until 5), TextSearch.find(text, "Alpha", ignoreCase = false))
        assertEquals(listOf(26 until 28, 28 until 30), TextSearch.find(text, "aa"))
        assertTrue(TextSearch.find(text, "").isEmpty())
        assertEquals(2, TextSearch.find("x x x", "x", limit = 2).size)
        assertEquals(1, TextSearch.next(matches, caret = 5))
        assertEquals(0, TextSearch.next(matches, caret = 23))
        assertEquals(2, TextSearch.previous(matches, caret = 0))
        assertEquals(0, TextSearch.previous(matches, caret = 11))
        assertEquals(-1, TextSearch.next(emptyList(), caret = 0))
        assertEquals(-1, TextSearch.previous(emptyList(), caret = 0))
    }

    @Test fun lineCountsAndFindLabelsForTheEditorChrome() {
        assertEquals(1, TextSearch.lineCount(""))
        assertEquals(3, TextSearch.lineCount("a\nb\nc"))
        assertEquals(4, TextSearch.lineCount("a\nb\nc\n"))
        assertEquals("No results", TextSearch.label(-1, 0))
        assertEquals("2 of 5", TextSearch.label(1, 5))
    }
}
