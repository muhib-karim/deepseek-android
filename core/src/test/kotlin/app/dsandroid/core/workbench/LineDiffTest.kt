package app.dsandroid.core.workbench

import kotlin.random.Random
import org.junit.Assert.*
import org.junit.Test

class LineDiffTest {
    @Test fun identicalTextHasNoChanges() {
        val stats = LineDiff.stats("a\nb\nc", "a\nb\nc")
        assertEquals(DiffStats(0, 0), stats)
        assertFalse(stats.changed)
        assertEquals("+0 -0 lines", stats.summary())
    }

    @Test fun countsInsertionsDeletionsAndReplacements() {
        assertEquals(DiffStats(2, 0), LineDiff.stats("", "a\nb"))
        assertEquals(DiffStats(0, 2), LineDiff.stats("a\nb", ""))
        assertEquals(DiffStats(0, 1), LineDiff.stats("a\nb\nc", "a\nc"))
        assertEquals(DiffStats(1, 0), LineDiff.stats("a\nc", "a\nb\nc"))
        assertEquals(DiffStats(1, 1), LineDiff.stats("a\nb\nc", "a\nB\nc"))
    }

    @Test fun summaryMatchesTheVersionPickerLabel() {
        val old = (1..10).joinToString("\n") { "line $it" }
        val new = (1..10).filter { it !in setOf(2, 5, 9) }.joinToString("\n") { "line $it" } + "\n" + (1..12).joinToString("\n") { "new $it" }
        val stats = LineDiff.stats(old, new)
        assertEquals("+12 -3 lines", stats.summary())
        assertTrue(stats.changed)
    }

    @Test fun lineEndingsAndAFinalNewlineAreNotChanges() {
        assertEquals(DiffStats(0, 0), LineDiff.stats("a\nb\n", "a\nb"))
        assertEquals(DiffStats(0, 0), LineDiff.stats("a\r\nb\r\n", "a\nb\n"))
        assertEquals(listOf("a", "", "b"), LineDiff.lines("a\n\nb\n"))
        assertEquals(emptyList<String>(), LineDiff.lines(""))
        assertEquals(listOf(""), LineDiff.lines("\n"))
    }

    @Test fun editScriptRebuildsBothSides() {
        val old = "<html>\n<body>\n<h1>Hi</h1>\n<p>Old</p>\n</body>\n</html>"
        val new = "<html>\n<head></head>\n<body>\n<h1>Hi</h1>\n<p>New</p>\n<p>More</p>\n</body>\n</html>"
        val script = LineDiff.diff(old, new)
        assertEquals(LineDiff.lines(old), script.filter { it.op != DiffOp.ADDED }.map { it.text })
        assertEquals(LineDiff.lines(new), script.filter { it.op != DiffOp.REMOVED }.map { it.text })
        assertEquals(DiffStats(3, 1), DiffStats(script.count { it.op == DiffOp.ADDED }, script.count { it.op == DiffOp.REMOVED }))
        assertEquals(LineDiff.stats(old, new), DiffStats(3, 1))
    }

    @Test fun randomInputsAreMinimalAgainstABruteForceLcs() {
        val random = Random(20261020)
        repeat(300) {
            val old = List(random.nextInt(0, 14)) { "abcd"[random.nextInt(4)].toString() }
            val new = List(random.nextInt(0, 14)) { "abcd"[random.nextInt(4)].toString() }
            val lcs = lcs(old, new)
            val stats = LineDiff.stats(old.joinToString("\n"), new.joinToString("\n"))
            assertEquals("old=$old new=$new", DiffStats(new.size - lcs, old.size - lcs), stats)
            val script = LineDiff.diff(old.joinToString("\n"), new.joinToString("\n"))
            assertEquals(old, script.filter { it.op != DiffOp.ADDED }.map { it.text })
            assertEquals(new, script.filter { it.op != DiffOp.REMOVED }.map { it.text })
            assertEquals(stats.added, script.count { it.op == DiffOp.ADDED })
            assertEquals(stats.removed, script.count { it.op == DiffOp.REMOVED })
        }
    }

    @Test fun veryDifferentLargeInputsStayBoundedAndExact() {
        val old = (1..30_000).joinToString("\n") { "old $it" }
        val new = (1..25_000).joinToString("\n") { "new $it" }
        assertEquals(DiffStats(25_000, 30_000), LineDiff.stats(old, new))
        val script = LineDiff.diff(old, new)
        assertEquals(25_000, script.count { it.op == DiffOp.ADDED })
        assertEquals(30_000, script.count { it.op == DiffOp.REMOVED })
    }

    @Test fun commonPrefixAndSuffixAreTrimmedForLongFiles() {
        val body = (1..20_000).map { "same $it" }
        val old = (body.take(10_000) + "before" + body.drop(10_000)).joinToString("\n")
        val new = (body.take(10_000) + listOf("after", "extra") + body.drop(10_000)).joinToString("\n")
        assertEquals(DiffStats(2, 1), LineDiff.stats(old, new))
    }

    private fun lcs(a: List<String>, b: List<String>): Int {
        val table = Array(a.size + 1) { IntArray(b.size + 1) }
        for (i in a.indices.reversed()) for (j in b.indices.reversed()) {
            table[i][j] = if (a[i] == b[j]) table[i + 1][j + 1] + 1 else maxOf(table[i + 1][j], table[i][j + 1])
        }
        return table[0][0]
    }
}
