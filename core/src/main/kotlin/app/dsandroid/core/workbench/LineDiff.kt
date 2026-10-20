package app.dsandroid.core.workbench

/** Lines added and removed between two versions; [summary] is the version picker's "+12 -3 lines" label. */
data class DiffStats(val added: Int, val removed: Int) {
    val changed: Boolean get() = added > 0 || removed > 0
    fun summary(): String = "+$added -$removed lines"
}

enum class DiffOp { SAME, ADDED, REMOVED }
data class DiffLine(val op: DiffOp, val text: String)

/**
 * Myers' O(ND) line diff run on the part between the common prefix and suffix. "\r\n" counts as "\n" and a
 * final newline does not start another line. Edit distances above the caps fall back to counting whole lines
 * (moved lines count as unchanged), so a huge rewrite never stalls the UI.
 */
object LineDiff {
    const val MAX_EDIT_DISTANCE = 4000
    const val MAX_SCRIPT_DISTANCE = 1000

    fun lines(text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        val normalized = text.replace("\r\n", "\n").replace('\r', '\n')
        val parts = normalized.split('\n')
        return if (normalized.endsWith('\n')) parts.dropLast(1) else parts
    }

    fun stats(old: String, new: String): DiffStats {
        val middle = Middle(lines(old), lines(new))
        val n = middle.a.size
        val m = middle.b.size
        val distance = forward(middle.a, middle.b, MAX_EDIT_DISTANCE, null) ?: return estimate(middle.a, middle.b)
        val common = (n + m - distance) / 2
        return DiffStats(m - common, n - common)
    }

    fun diff(old: String, new: String): List<DiffLine> {
        val before = lines(old)
        val after = lines(new)
        val middle = Middle(before, after)
        val out = ArrayList<DiffLine>(before.size + after.size)
        for (index in 0 until middle.prefix) out += DiffLine(DiffOp.SAME, before[index])
        val trace = ArrayList<IntArray>()
        val distance = forward(middle.a, middle.b, MAX_SCRIPT_DISTANCE, trace)
        if (distance == null) {
            middle.oldLines.forEach { out += DiffLine(DiffOp.REMOVED, it) }
            middle.newLines.forEach { out += DiffLine(DiffOp.ADDED, it) }
        } else out += backtrack(middle, trace, distance)
        for (index in before.size - middle.suffix until before.size) out += DiffLine(DiffOp.SAME, before[index])
        return out
    }

    /** Lines between the shared prefix and suffix, as ids so comparisons are integer checks. */
    private class Middle(before: List<String>, after: List<String>) {
        val prefix: Int
        val suffix: Int
        val oldLines: List<String>
        val newLines: List<String>
        val a: IntArray
        val b: IntArray
        init {
            var start = 0
            while (start < before.size && start < after.size && before[start] == after[start]) start++
            var end = 0
            while (end < before.size - start && end < after.size - start && before[before.size - 1 - end] == after[after.size - 1 - end]) end++
            prefix = start
            suffix = end
            oldLines = before.subList(start, before.size - end)
            newLines = after.subList(start, after.size - end)
            val ids = HashMap<String, Int>()
            a = IntArray(oldLines.size) { ids.getOrPut(oldLines[it]) { ids.size } }
            b = IntArray(newLines.size) { ids.getOrPut(newLines[it]) { ids.size } }
        }
    }

    /**
     * Length of the shortest edit script, or null above [cap]. When [trace] is given, the furthest-reaching x for
     * diagonals -d..d is recorded after each step d (index k + d).
     */
    private fun forward(a: IntArray, b: IntArray, cap: Int, trace: MutableList<IntArray>?): Int? {
        val n = a.size
        val m = b.size
        val limit = minOf(cap, n + m)
        val offset = limit + 1
        val v = IntArray(2 * limit + 3)
        for (d in 0..limit) {
            var k = -d
            while (k <= d) {
                var x = if (k == -d || (k != d && v[offset + k - 1] < v[offset + k + 1])) v[offset + k + 1] else v[offset + k - 1] + 1
                var y = x - k
                while (x < n && y < m && a[x] == b[y]) { x++; y++ }
                v[offset + k] = x
                if (x >= n && y >= m) {
                    trace?.add(IntArray(2 * d + 1) { v[offset - d + it] })
                    return d
                }
                k += 2
            }
            trace?.add(IntArray(2 * d + 1) { v[offset - d + it] })
        }
        return null
    }

    private fun backtrack(middle: Middle, trace: List<IntArray>, distance: Int): List<DiffLine> {
        val reversed = ArrayList<DiffLine>()
        var x = middle.a.size
        var y = middle.b.size
        for (d in distance downTo 1) {
            val previous = trace[d - 1]
            fun at(k: Int) = previous[k + d - 1]
            val k = x - y
            val down = k == -d || (k != d && at(k - 1) < at(k + 1))
            val previousK = if (down) k + 1 else k - 1
            val previousX = at(previousK)
            val previousY = previousX - previousK
            val startX = if (down) previousX else previousX + 1
            while (x > startX) { reversed += DiffLine(DiffOp.SAME, middle.oldLines[x - 1]); x--; y-- }
            if (down) reversed += DiffLine(DiffOp.ADDED, middle.newLines[y - 1])
            else reversed += DiffLine(DiffOp.REMOVED, middle.oldLines[x - 1])
            x = previousX
            y = previousY
        }
        while (x > 0 && y > 0) { reversed += DiffLine(DiffOp.SAME, middle.oldLines[x - 1]); x--; y-- }
        return reversed.asReversed()
    }

    /** Upper-bound fallback: a line counts as unchanged as often as it appears on both sides. */
    private fun estimate(a: IntArray, b: IntArray): DiffStats {
        val counts = HashMap<Int, Int>()
        a.forEach { counts[it] = (counts[it] ?: 0) + 1 }
        var added = 0
        b.forEach { id ->
            val left = counts[id] ?: 0
            if (left > 0) counts[id] = left - 1 else added++
        }
        return DiffStats(added, counts.values.sum())
    }
}
