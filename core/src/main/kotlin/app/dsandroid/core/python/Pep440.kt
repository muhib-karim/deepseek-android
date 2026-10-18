package app.dsandroid.core.python

/** The small PEP 440 / PEP 508 subset needed to pick pure-Python wheels; anything unparseable is treated as "no match". */
object Pep440 {
    private val VERSION = Regex("(?i)v?(\\d+(?:\\.\\d+)*)(?:[-_.]?(a|alpha|b|beta|rc|c|pre|preview)[-_.]?(\\d*))?(?:[-_.]?(post|rev|r)[-_.]?(\\d*))?(?:[-_.]?(dev)[-_.]?(\\d*))?(?:\\+[a-z0-9.]+)?")
    private val NAME = Regex("[A-Za-z0-9]([A-Za-z0-9._-]*[A-Za-z0-9])?")
    private val SPECIFIER = Regex("\\s*(===|==|!=|~=|<=|>=|<|>)\\s*([A-Za-z0-9.*+!_-]+)\\s*")

    data class Version(val release: List<Int>, val pre: Pair<Int, Int>?, val post: Int?, val dev: Int?) : Comparable<Version> {
        val isPrerelease: Boolean get() = pre != null || dev != null
        override fun compareTo(other: Version): Int {
            val size = maxOf(release.size, other.release.size)
            for (index in 0 until size) {
                val difference = release.getOrElse(index) { 0 }.compareTo(other.release.getOrElse(index) { 0 })
                if (difference != 0) return difference
            }
            // Order within one release: dev < pre-releases < final < post.
            fun phase(version: Version): List<Int> = when {
                version.pre == null && version.post == null && version.dev != null -> listOf(-1, 0, 0, version.dev)
                version.pre != null -> listOf(0, version.pre.first, version.pre.second, version.dev ?: Int.MAX_VALUE)
                else -> listOf(1, version.post ?: -1, 0, version.dev ?: Int.MAX_VALUE)
            }
            val left = phase(this)
            val right = phase(other)
            for (index in left.indices) {
                val difference = left[index].compareTo(right[index])
                if (difference != 0) return difference
            }
            return 0
        }
    }

    data class Requirement(val name: String, val specifiers: List<Pair<String, String>>, val marker: String?)

    fun normalize(name: String): String = name.lowercase().replace(Regex("[-_.]+"), "-")

    fun version(text: String): Version? {
        val match = VERSION.matchEntire(text.trim()) ?: return null
        val release = match.groupValues[1].split('.').map { it.toIntOrNull() ?: return null }
        val pre = match.groupValues[2].takeIf { it.isNotEmpty() }?.let { tag ->
            val rank = when (tag.lowercase()) { "a", "alpha" -> 0; "b", "beta" -> 1; else -> 2 }
            rank to (match.groupValues[3].toIntOrNull() ?: 0)
        }
        val post = match.groupValues[4].takeIf { it.isNotEmpty() }?.let { match.groupValues[5].toIntOrNull() ?: 0 }
        val dev = match.groupValues[6].takeIf { it.isNotEmpty() }?.let { match.groupValues[7].toIntOrNull() ?: 0 }
        return Version(release, pre, post, dev)
    }

    /** "name[extra] (>=1, <2) ; marker" without URLs. Throws for anything else. */
    fun requirement(text: String): Requirement {
        val (body, marker) = text.split(';', limit = 2).let { it[0].trim() to it.getOrNull(1)?.trim()?.ifEmpty { null } }
        require(body.isNotEmpty() && '@' !in body && "://" !in body) { "Unsupported requirement: $text" }
        val nameMatch = NAME.find(body)?.takeIf { it.range.first == 0 } ?: throw IllegalArgumentException("Invalid package name in $text")
        var rest = body.substring(nameMatch.range.last + 1).trim()
        if (rest.startsWith("[")) {
            val end = rest.indexOf(']')
            require(end > 0) { "Invalid extras in $text" }
            rest = rest.substring(end + 1).trim()
        }
        rest = rest.removePrefix("(").removeSuffix(")").trim()
        val specifiers = if (rest.isEmpty()) emptyList() else rest.split(',').map { part ->
            val spec = SPECIFIER.matchEntire(part) ?: throw IllegalArgumentException("Invalid version specifier in $text")
            spec.groupValues[1] to spec.groupValues[2]
        }
        return Requirement(normalize(nameMatch.value), specifiers, marker)
    }

    fun satisfies(version: String, specifiers: List<Pair<String, String>>): Boolean {
        val candidate = version(version) ?: return false
        return specifiers.all { (operator, target) -> matches(candidate, version, operator, target) }
    }

    private fun matches(candidate: Version, raw: String, operator: String, target: String): Boolean {
        if (operator == "===") return raw == target
        if ((operator == "==" || operator == "!=") && target.endsWith(".*")) {
            val prefix = version(target.removeSuffix(".*"))?.release ?: return false
            val equal = candidate.release.size >= prefix.size && candidate.release.subList(0, prefix.size) == prefix
            return if (operator == "==") equal else !equal
        }
        val wanted = version(target) ?: return false
        val order = candidate.compareTo(wanted)
        return when (operator) {
            "==" -> order == 0
            "!=" -> order != 0
            ">=" -> order >= 0
            "<=" -> order <= 0
            ">" -> order > 0
            "<" -> order < 0
            "~=" -> {
                if (wanted.release.size < 2 || order < 0) false
                else {
                    val prefix = wanted.release.subList(0, wanted.release.size - 1)
                    candidate.release.size >= prefix.size && candidate.release.subList(0, prefix.size) == prefix
                }
            }
            else -> false
        }
    }

    /** True when the interpreter version satisfies a requires_python value such as ">=3.8,!=3.9.*". */
    fun pythonCompatible(requiresPython: String?, pythonVersion: String): Boolean {
        if (requiresPython.isNullOrBlank()) return true
        val specifiers = requiresPython.split(',').mapNotNull { part ->
            SPECIFIER.matchEntire(part)?.let { it.groupValues[1] to it.groupValues[2] }
        }
        return specifiers.isNotEmpty() && satisfies(pythonVersion, specifiers)
    }

    /** PEP 508 marker evaluation over a fixed environment; unknown syntax evaluates to false. */
    fun marker(expression: String, environment: Map<String, String>): Boolean = runCatching { MarkerParser(expression, environment).parse() }.getOrDefault(false)

    private class MarkerParser(text: String, private val environment: Map<String, String>) {
        private val tokens = Regex("\\s*(\\(|\\)|===|==|!=|~=|<=|>=|<|>|not\\s+in\\b|in\\b|and\\b|or\\b|'[^']*'|\"[^\"]*\"|[A-Za-z_][A-Za-z0-9_.]*)")
            .findAll(text).map { it.groupValues[1] }.toList()
            .also { found -> require(found.joinToString("").replace(" ", "") == text.replace(Regex("\\s+"), "")) { "Unknown marker syntax" } }
        private var position = 0

        fun parse(): Boolean = or().also { require(position == tokens.size) }
        private fun or(): Boolean {
            var value = and()
            while (peek() == "or") { position++; val right = and(); value = value || right }
            return value
        }
        private fun and(): Boolean {
            var value = atom()
            while (peek() == "and") { position++; val right = atom(); value = value && right }
            return value
        }
        private fun atom(): Boolean {
            if (peek() == "(") {
                position++
                val value = or()
                require(next() == ")")
                return value
            }
            val left = operand()
            val operator = next().replace(Regex("\\s+"), " ")
            val right = operand()
            return compare(left, operator, right)
        }
        private fun operand(): Pair<String, Boolean> {
            val token = next()
            return if (token.startsWith("'") || token.startsWith("\"")) token.substring(1, token.length - 1) to false
            else (environment[token] ?: throw IllegalArgumentException("Unknown marker variable")) to (token == "python_version" || token == "python_full_version")
        }
        private fun compare(left: Pair<String, Boolean>, operator: String, right: Pair<String, Boolean>): Boolean {
            val versions = left.second || right.second
            return when (operator) {
                "in" -> left.first in right.first
                "not in" -> left.first !in right.first
                "==", "!=", "<", "<=", ">", ">=", "~=", "===" ->
                    if (versions && version(left.first) != null && version(right.first) != null) satisfies(left.first, listOf(operator to right.first))
                    else when (operator) {
                        "==", "===" -> left.first == right.first
                        "!=" -> left.first != right.first
                        else -> throw IllegalArgumentException("Ordering on strings")
                    }
                else -> throw IllegalArgumentException("Unknown operator")
            }
        }
        private fun peek(): String? = tokens.getOrNull(position)
        private fun next(): String = tokens.getOrNull(position++) ?: throw IllegalArgumentException("Unexpected end")
    }
}
