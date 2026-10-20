package app.dsandroid.core.editor

enum class TokenType { KEYWORD, STRING, COMMENT, NUMBER, TAG, ATTRIBUTE, HEADING }

/** A highlighted span: [start] inclusive, [end] exclusive. Text between tokens is plain. */
data class Token(val start: Int, val end: Int, val type: TokenType)

/**
 * A small single-pass tokenizer for the editor's highlighting: keywords, strings, comments and numbers per
 * language family, plus tags and attributes for markup, selectors and properties for CSS, keys for JSON and
 * YAML. It never fails on malformed input; tokens are ordered and never overlap.
 */
object SyntaxTokenizer {
    /** Larger documents are shown without highlighting so typing stays responsive. */
    const val MAX_CHARS = 200_000

    fun tokenize(text: String, language: EditorLanguage): List<Token> {
        if (text.length > MAX_CHARS) return emptyList()
        val lexer = Lexer(text)
        val end = text.length
        when (language.family) {
            LanguageFamily.C_LIKE -> lexer.cLike(0, end, Keywords.forLanguage(language), templates = language != EditorLanguage.KOTLIN,
                rawStrings = language == EditorLanguage.KOTLIN)
            LanguageFamily.PYTHON -> lexer.python(0, end)
            LanguageFamily.SHELL -> lexer.shell(0, end)
            LanguageFamily.SQL -> lexer.sql(0, end)
            LanguageFamily.MARKUP -> lexer.markup(0, end)
            LanguageFamily.CSS -> lexer.css(0, end)
            LanguageFamily.JSON -> lexer.json(0, end)
            LanguageFamily.MARKDOWN -> lexer.markdown(0, end)
            LanguageFamily.CSV -> lexer.csv(0, end)
            LanguageFamily.YAML -> lexer.yaml(0, end)
            LanguageFamily.PLAIN -> Unit
        }
        return lexer.tokens
    }
}

private object Keywords {
    private val kotlinWords = setOf(
        "as", "break", "class", "continue", "do", "else", "false", "for", "fun", "if", "in", "interface", "is", "null", "object",
        "package", "return", "super", "this", "throw", "true", "try", "typealias", "typeof", "val", "var", "when", "while", "by",
        "catch", "constructor", "finally", "import", "init", "where", "abstract", "annotation", "companion", "const", "crossinline",
        "data", "enum", "expect", "actual", "external", "final", "infix", "inline", "inner", "internal", "lateinit", "noinline",
        "open", "operator", "out", "override", "private", "protected", "public", "reified", "sealed", "suspend", "tailrec", "vararg",
    )
    private val javaScript = setOf(
        "break", "case", "catch", "class", "const", "continue", "debugger", "default", "delete", "do", "else", "export", "extends",
        "false", "finally", "for", "function", "if", "import", "in", "instanceof", "let", "new", "null", "return", "super", "switch",
        "this", "throw", "true", "try", "typeof", "var", "void", "while", "with", "yield", "async", "await", "of", "static", "undefined",
    )
    private val typeScript = javaScript + setOf(
        "interface", "type", "readonly", "number", "string", "boolean", "any", "unknown", "never", "enum", "implements", "private",
        "public", "protected", "namespace", "declare", "abstract", "as", "keyof", "infer", "is", "module", "satisfies", "bigint",
        "symbol", "object",
    )
    val python = setOf(
        "False", "None", "True", "and", "as", "assert", "async", "await", "break", "class", "continue", "def", "del", "elif", "else",
        "except", "finally", "for", "from", "global", "if", "import", "in", "is", "lambda", "nonlocal", "not", "or", "pass", "raise",
        "return", "try", "while", "with", "yield",
    )
    val shell = setOf(
        "if", "then", "else", "elif", "fi", "for", "while", "until", "do", "done", "case", "esac", "in", "function", "select",
        "time", "return", "export", "local", "readonly", "declare", "unset", "break", "continue", "exit", "source", "alias", "shift", "trap",
    )
    val sql = setOf(
        "SELECT", "FROM", "WHERE", "AND", "OR", "NOT", "NULL", "IS", "IN", "LIKE", "BETWEEN", "AS", "ON", "JOIN", "INNER", "LEFT",
        "RIGHT", "FULL", "OUTER", "CROSS", "GROUP", "BY", "ORDER", "HAVING", "LIMIT", "OFFSET", "DISTINCT", "UNION", "ALL", "INSERT",
        "INTO", "VALUES", "UPDATE", "SET", "DELETE", "CREATE", "TABLE", "VIEW", "INDEX", "DROP", "ALTER", "ADD", "COLUMN", "PRIMARY",
        "KEY", "FOREIGN", "REFERENCES", "UNIQUE", "CHECK", "DEFAULT", "CONSTRAINT", "CASE", "WHEN", "THEN", "ELSE", "END", "EXISTS",
        "WITH", "RETURNING", "ASC", "DESC", "TRUE", "FALSE", "IF", "REPLACE", "BEGIN", "COMMIT", "ROLLBACK", "TRANSACTION", "INTEGER",
        "INT", "TEXT", "VARCHAR", "CHAR", "BOOLEAN", "REAL", "FLOAT", "DOUBLE", "DATE", "TIMESTAMP", "COUNT", "SUM", "AVG", "MIN", "MAX",
    )
    val yamlWords = setOf("true", "false", "null", "yes", "no", "on", "off", "~")
    val jsonWords = setOf("true", "false", "null")
    val pythonStringPrefixes = setOf("r", "b", "f", "u", "rb", "br", "fr", "rf")

    fun forLanguage(language: EditorLanguage): Set<String> = when (language) {
        EditorLanguage.KOTLIN -> kotlinWords
        EditorLanguage.TYPESCRIPT -> typeScript
        else -> javaScript
    }
}

private class Lexer(private val text: String) {
    val tokens = ArrayList<Token>()
    private val yamlNumber = Regex("[-+]?(\\d[\\d_]*(\\.\\d*)?|\\.\\d+)([eE][-+]?\\d+)?|0x[0-9a-fA-F]+|0o[0-7]+")
    private val csvNumber = Regex("-?\\d+(\\.\\d+)?([eE][-+]?\\d+)?")
    private val heading = Regex("^ {0,3}#{1,6}(\\s.*)?$")
    private val quote = Regex("^ {0,3}>")
    private val listMarker = Regex("^(\\s*)([-*+]|\\d{1,9}[.)])\\s+")
    private val fenceOpening = Regex("^ {0,3}(`{3,}|~{3,})")

    private fun add(start: Int, end: Int, type: TokenType) {
        if (end > start && start >= (tokens.lastOrNull()?.end ?: 0)) tokens += Token(start, end, type)
    }
    private fun starts(prefix: String, at: Int, until: Int) = at + prefix.length <= until && text.startsWith(prefix, at)
    private fun find(needle: String, from: Int, until: Int): Int {
        val index = text.indexOf(needle, from)
        return if (index >= 0 && index + needle.length <= until) index else -1
    }
    private fun lineEnd(from: Int, until: Int): Int {
        val index = text.indexOf('\n', from)
        return if (index < 0 || index > until) until else index
    }
    private fun trimmedEnd(start: Int, end: Int): Int {
        var at = end
        while (at > start && text[at - 1].isWhitespace()) at--
        return at
    }
    private fun identStart(c: Char) = c.isLetter() || c == '_'
    private fun identPart(c: Char) = c.isLetterOrDigit() || c == '_'

    /** End (exclusive) of a string opened by [delimiter] at [start]. Single-line strings stop before the newline. */
    private fun quoted(start: Int, until: Int, delimiter: String, escapes: Boolean, multiline: Boolean): Int {
        var at = start + delimiter.length
        while (at < until) {
            val c = text[at]
            if (escapes && c == '\\') { at += 2; continue }
            if (!multiline && c == '\n') return at
            if (starts(delimiter, at, until)) return at + delimiter.length
            at++
        }
        return until
    }

    private fun number(start: Int, until: Int): Int {
        var at = start
        if (at + 1 < until && text[at] == '0' && text[at + 1] in "xXbBoO") {
            at += 2
            while (at < until && (text[at].isLetterOrDigit() || text[at] == '_')) at++
            return at
        }
        while (at < until && (text[at].isDigit() || text[at] == '_')) at++
        if (at + 1 < until && text[at] == '.' && text[at + 1].isDigit()) {
            at++
            while (at < until && (text[at].isDigit() || text[at] == '_')) at++
        }
        if (at < until && text[at] in "eE") {
            var exponent = at + 1
            if (exponent < until && text[exponent] in "+-") exponent++
            if (exponent < until && text[exponent].isDigit()) {
                at = exponent
                while (at < until && text[at].isDigit()) at++
            }
        }
        while (at < until && text[at].isLetter()) at++
        return at
    }

    private fun lineComment(start: Int, until: Int): Int {
        val end = lineEnd(start, until)
        add(start, trimmedEnd(start, end), TokenType.COMMENT)
        return end
    }

    private fun blockComment(start: Int, until: Int, close: String): Int {
        val index = find(close, start + 2, until)
        val end = if (index < 0) until else index + close.length
        add(start, end, TokenType.COMMENT)
        return end
    }

    fun cLike(from: Int, until: Int, keywords: Set<String>, templates: Boolean, rawStrings: Boolean) {
        var at = from
        while (at < until) {
            val c = text[at]
            at = when {
                starts("//", at, until) -> lineComment(at, until)
                starts("/*", at, until) -> blockComment(at, until, "*/")
                rawStrings && starts("\"\"\"", at, until) -> {
                    val close = find("\"\"\"", at + 3, until)
                    var end = if (close < 0) until else close + 3
                    while (end < until && text[end] == '"') end++
                    add(at, end, TokenType.STRING); end
                }
                c == '"' || c == '\'' || (templates && c == '`') -> {
                    val end = quoted(at, until, c.toString(), escapes = true, multiline = c == '`')
                    add(at, end, TokenType.STRING); end
                }
                c == '@' && at + 1 < until && identStart(text[at + 1]) -> {
                    var end = at + 1
                    while (end < until && (identPart(text[end]) || text[end] == '.')) end++
                    add(at, end, TokenType.ATTRIBUTE); end
                }
                identStart(c) || c == '$' -> {
                    var end = at + 1
                    while (end < until && (identPart(text[end]) || text[end] == '$')) end++
                    if (text.substring(at, end) in keywords) add(at, end, TokenType.KEYWORD)
                    end
                }
                c.isDigit() -> number(at, until).also { add(at, it, TokenType.NUMBER) }
                else -> at + 1
            }
        }
    }

    private fun pythonString(start: Int, until: Int): Int {
        val delimiter = text[start].toString()
        val triple = delimiter.repeat(3)
        return if (starts(triple, start, until)) quoted(start, until, triple, escapes = true, multiline = true)
        else quoted(start, until, delimiter, escapes = true, multiline = false)
    }

    fun python(from: Int, until: Int) {
        var at = from
        while (at < until) {
            val c = text[at]
            at = when {
                c == '#' -> lineComment(at, until)
                c == '"' || c == '\'' -> pythonString(at, until).also { add(at, it, TokenType.STRING) }
                c == '@' && at + 1 < until && identStart(text[at + 1]) && (at == from || text[at - 1].isWhitespace()) -> {
                    var end = at + 1
                    while (end < until && (identPart(text[end]) || text[end] == '.')) end++
                    add(at, end, TokenType.ATTRIBUTE); end
                }
                identStart(c) -> {
                    var end = at + 1
                    while (end < until && identPart(text[end])) end++
                    val word = text.substring(at, end)
                    if (word.lowercase() in Keywords.pythonStringPrefixes && end < until && (text[end] == '"' || text[end] == '\'')) {
                        pythonString(end, until).also { add(at, it, TokenType.STRING) }
                    } else {
                        if (word in Keywords.python) add(at, end, TokenType.KEYWORD)
                        end
                    }
                }
                c.isDigit() -> number(at, until).also { add(at, it, TokenType.NUMBER) }
                else -> at + 1
            }
        }
    }

    fun shell(from: Int, until: Int) {
        var at = from
        while (at < until) {
            val c = text[at]
            at = when {
                c == '#' && (at == from || text[at - 1].isWhitespace() || text[at - 1] == ';') -> lineComment(at, until)
                c == '\'' -> quoted(at, until, "'", escapes = false, multiline = true).also { add(at, it, TokenType.STRING) }
                c == '"' -> quoted(at, until, "\"", escapes = true, multiline = true).also { add(at, it, TokenType.STRING) }
                c == '$' && at + 1 < until && text[at + 1] == '{' -> {
                    val close = find("}", at + 2, until)
                    val end = if (close < 0) lineEnd(at, until) else close + 1
                    add(at, end, TokenType.ATTRIBUTE); end
                }
                c == '$' && at + 1 < until && (identStart(text[at + 1]) || text[at + 1].isDigit() || text[at + 1] in "@*#?$!-") -> {
                    var end = at + 2
                    if (identStart(text[at + 1])) while (end < until && identPart(text[end])) end++
                    add(at, end, TokenType.ATTRIBUTE); end
                }
                identStart(c) -> {
                    var end = at + 1
                    while (end < until && identPart(text[end])) end++
                    if (text.substring(at, end) in Keywords.shell) add(at, end, TokenType.KEYWORD)
                    end
                }
                c.isDigit() && (at == from || !identPart(text[at - 1])) -> {
                    var end = at
                    while (end < until && text[end].isDigit()) end++
                    add(at, end, TokenType.NUMBER); end
                }
                else -> at + 1
            }
        }
    }

    fun sql(from: Int, until: Int) {
        var at = from
        while (at < until) {
            val c = text[at]
            at = when {
                starts("--", at, until) -> lineComment(at, until)
                starts("/*", at, until) -> blockComment(at, until, "*/")
                c == '\'' -> {
                    var end = at + 1
                    while (end < until) {
                        if (text[end] == '\'') {
                            if (end + 1 < until && text[end + 1] == '\'') end += 2 else { end++; break }
                        } else end++
                    }
                    end = minOf(end, until)
                    add(at, end, TokenType.STRING); end
                }
                c == '"' || c == '`' -> quoted(at, until, c.toString(), escapes = false, multiline = false).also { add(at, it, TokenType.STRING) }
                identStart(c) -> {
                    var end = at + 1
                    while (end < until && identPart(text[end])) end++
                    if (text.substring(at, end).uppercase() in Keywords.sql) add(at, end, TokenType.KEYWORD)
                    end
                }
                c.isDigit() -> number(at, until).also { add(at, it, TokenType.NUMBER) }
                else -> at + 1
            }
        }
    }

    private fun tagNameChar(c: Char) = c.isLetterOrDigit() || c in ":._-"

    fun markup(from: Int, until: Int) {
        var at = from
        while (at < until) {
            if (text[at] != '<') { at++; continue }
            at = when {
                starts("<!--", at, until) -> blockCommentAt(at, until, "-->", 4)
                starts("<![CDATA[", at, until) -> {
                    val close = find("]]>", at + 9, until)
                    val end = if (close < 0) until else close + 3
                    add(at, end, TokenType.STRING); end
                }
                starts("<!", at, until) || starts("<?", at, until) -> {
                    val close = find(">", at + 2, until)
                    val end = if (close < 0) until else close + 1
                    add(at, end, TokenType.KEYWORD); end
                }
                at + 1 < until && (text[at + 1].isLetter() || (text[at + 1] == '/' && at + 2 < until && text[at + 2].isLetter())) -> tag(at, until)
                else -> at + 1
            }
        }
    }

    private fun blockCommentAt(start: Int, until: Int, close: String, openLength: Int): Int {
        val index = find(close, start + openLength, until)
        val end = if (index < 0) until else index + close.length
        add(start, end, TokenType.COMMENT)
        return end
    }

    /** One start or end tag with its attributes; the body of script and style elements is tokenized as JavaScript or CSS. */
    private fun tag(start: Int, until: Int): Int {
        val closing = text[start + 1] == '/'
        val nameStart = start + if (closing) 2 else 1
        var at = nameStart
        while (at < until && tagNameChar(text[at])) at++
        val name = text.substring(nameStart, at).lowercase()
        add(start, at, TokenType.TAG)
        var opened = false
        while (at < until) {
            val c = text[at]
            if (c == '>') { add(at, at + 1, TokenType.TAG); at++; opened = !closing; break }
            if (c == '/' && at + 1 < until && text[at + 1] == '>') { add(at, at + 2, TokenType.TAG); at += 2; break }
            if (c == '<') break
            at = when {
                c.isWhitespace() || c == '/' -> at + 1
                c == '=' -> {
                    var value = at + 1
                    while (value < until && text[value].isWhitespace()) value++
                    if (value < until && (text[value] == '"' || text[value] == '\'')) {
                        quoted(value, until, text[value].toString(), escapes = false, multiline = true).also { add(value, it, TokenType.STRING) }
                    } else {
                        var end = value
                        while (end < until && !text[end].isWhitespace() && text[end] != '>' && text[end] != '<') end++
                        add(value, end, TokenType.STRING)
                        maxOf(end, at + 1)
                    }
                }
                c == '"' || c == '\'' -> quoted(at, until, c.toString(), escapes = false, multiline = true).also { add(at, it, TokenType.STRING) }
                else -> {
                    var end = at
                    while (end < until && !text[end].isWhitespace() && text[end] !in "=>/<\"'") end++
                    add(at, end, TokenType.ATTRIBUTE)
                    maxOf(end, at + 1)
                }
            }
        }
        if (opened && (name == "script" || name == "style")) {
            val close = text.indexOf("</$name", at, ignoreCase = true).let { if (it < 0 || it > until) until else it }
            if (name == "script") cLike(at, close, Keywords.forLanguage(EditorLanguage.JAVASCRIPT), templates = true, rawStrings = false) else css(at, close)
            return close
        }
        return at
    }

    private fun cssIdentStart(c: Char) = c.isLetter() || c == '_'
    private fun cssNameChar(c: Char) = c.isLetterOrDigit() || c == '_' || c == '-'
    private fun cssIdent(start: Int, until: Int): Int {
        var end = start
        if (end < until && text[end] == '-') end++
        while (end < until && cssNameChar(text[end])) end++
        return end
    }

    /** A CSS property is a name followed by ':' whose value ends at ';' or '}' before any '{'. */
    private fun cssProperty(nameEnd: Int, until: Int): Boolean {
        var at = nameEnd
        while (at < until && (text[at] == ' ' || text[at] == '\t')) at++
        if (at >= until || text[at] != ':') return false
        at++
        while (at < until) {
            when (text[at]) { ';', '}' -> return true; '{' -> return false }
            at++
        }
        return true
    }

    fun css(from: Int, until: Int) {
        var at = from
        var depth = 0
        var value = false
        while (at < until) {
            val c = text[at]
            at = when {
                starts("/*", at, until) -> blockComment(at, until, "*/")
                c == '"' || c == '\'' -> quoted(at, until, c.toString(), escapes = true, multiline = false).also { add(at, it, TokenType.STRING) }
                c == '{' -> { depth++; value = false; at + 1 }
                c == '}' -> { depth = maxOf(0, depth - 1); value = false; at + 1 }
                c == ';' -> { value = false; at + 1 }
                c == '@' && at + 1 < until && cssIdentStart(text[at + 1]) -> cssIdent(at + 1, until).also { add(at, it, TokenType.KEYWORD) }
                c == '!' && at + 1 < until && text[at + 1].isLetter() -> {
                    var end = at + 1
                    while (end < until && text[end].isLetter()) end++
                    add(at, end, TokenType.KEYWORD); end
                }
                c == '#' && at + 1 < until && cssNameChar(text[at + 1]) -> {
                    var end = at + 1
                    while (end < until && cssNameChar(text[end])) end++
                    add(at, end, if (value) TokenType.NUMBER else TokenType.TAG); end
                }
                c == '.' && at + 1 < until && cssIdentStart(text[at + 1]) && !value -> cssIdent(at + 1, until).also { add(at, it, TokenType.TAG) }
                c.isDigit() || (c in ".+-" && at + 1 < until && text[at + 1].isDigit()) -> {
                    var end = at + 1
                    while (end < until && (text[end].isDigit() || text[end] == '.')) end++
                    while (end < until && (text[end].isLetter() || text[end] == '%')) end++
                    add(at, end, TokenType.NUMBER); end
                }
                cssIdentStart(c) || (c == '-' && at + 1 < until && cssIdentStart(text[at + 1])) -> {
                    val end = cssIdent(at, until)
                    if (!value) {
                        if (depth > 0 && cssProperty(end, until)) { add(at, end, TokenType.ATTRIBUTE); value = true }
                        else add(at, end, TokenType.TAG)
                    }
                    end
                }
                else -> at + 1
            }
        }
    }

    fun json(from: Int, until: Int) {
        var at = from
        while (at < until) {
            val c = text[at]
            at = when {
                c == '"' -> {
                    val end = quoted(at, until, "\"", escapes = true, multiline = false)
                    var next = end
                    while (next < until && text[next].isWhitespace()) next++
                    add(at, end, if (next < until && text[next] == ':') TokenType.ATTRIBUTE else TokenType.STRING); end
                }
                c.isDigit() || (c == '-' && at + 1 < until && text[at + 1].isDigit()) ->
                    number(if (c == '-') at + 1 else at, until).also { add(at, it, TokenType.NUMBER) }
                c.isLetter() -> {
                    var end = at + 1
                    while (end < until && text[end].isLetter()) end++
                    if (text.substring(at, end) in Keywords.jsonWords) add(at, end, TokenType.KEYWORD)
                    end
                }
                else -> at + 1
            }
        }
    }

    fun markdown(from: Int, until: Int) {
        var lineStart = from
        var fenceStart = -1
        var fenceChar = ' '
        var fenceLength = 0
        while (lineStart < until) {
            val end = lineEnd(lineStart, until)
            val line = text.substring(lineStart, end).trimEnd('\r')
            if (fenceStart >= 0) {
                val trimmed = line.trim()
                if (line.takeWhile { it == ' ' }.length <= 3 && trimmed.length >= fenceLength && trimmed.all { it == fenceChar }) {
                    add(fenceStart, trimmedEnd(fenceStart, end), TokenType.STRING)
                    fenceStart = -1
                }
            } else {
                val fence = fenceOpening.find(line)
                val indent = line.takeWhile { it == ' ' }.length
                val list = listMarker.find(line)
                when {
                    fence != null -> {
                        fenceStart = lineStart + indent
                        fenceChar = fence.groupValues[1].first()
                        fenceLength = fence.groupValues[1].length
                    }
                    heading.matches(line) -> add(lineStart + indent, trimmedEnd(lineStart, end), TokenType.HEADING)
                    quote.containsMatchIn(line) -> add(lineStart + indent, trimmedEnd(lineStart, end), TokenType.COMMENT)
                    list != null -> {
                        val markerStart = lineStart + list.groupValues[1].length
                        add(markerStart, markerStart + list.groupValues[2].length, TokenType.KEYWORD)
                        markdownInline(lineStart + list.value.length, end)
                    }
                    else -> markdownInline(lineStart, end)
                }
            }
            lineStart = end + 1
        }
        if (fenceStart >= 0) add(fenceStart, trimmedEnd(fenceStart, until), TokenType.STRING)
    }

    private fun markdownInline(from: Int, until: Int) {
        var at = from
        while (at < until) {
            val c = text[at]
            at = when {
                c == '`' -> {
                    var run = 0
                    while (at + run < until && text[at + run] == '`') run++
                    val close = find("`".repeat(run), at + run, until)
                    if (close >= 0) (close + run).also { add(at, it, TokenType.STRING) } else at + run
                }
                (c == '*' || c == '_') && at + 1 < until && text[at + 1] == c -> {
                    val close = find("$c$c", at + 2, until)
                    if (close > at + 2) (close + 2).also { add(at, it, TokenType.KEYWORD) } else at + 2
                }
                c == '*' && at + 1 < until && !text[at + 1].isWhitespace() -> {
                    val close = text.indexOf('*', at + 1)
                    if (close in (at + 2) until until && !text[close - 1].isWhitespace()) (close + 1).also { add(at, it, TokenType.KEYWORD) } else at + 1
                }
                c == ']' && at + 1 < until && text[at + 1] == '(' -> {
                    val close = text.indexOf(')', at + 2)
                    if (close in (at + 2) until until) (close + 1).also { add(at + 1, it, TokenType.ATTRIBUTE) } else at + 1
                }
                else -> at + 1
            }
        }
    }

    fun csv(from: Int, until: Int) {
        var at = from
        var header = true
        while (at <= until) {
            if (at < until && text[at] == '"') {
                var end = at + 1
                while (end < until) {
                    if (text[end] == '"') {
                        if (end + 1 < until && text[end + 1] == '"') end += 2 else { end++; break }
                    } else end++
                }
                end = minOf(end, until)
                add(at, end, if (header) TokenType.ATTRIBUTE else TokenType.STRING)
                at = end
                while (at < until && text[at] != ',' && text[at] != '\n') at++
            } else {
                var end = at
                while (end < until && text[end] != ',' && text[end] != '\n') end++
                var start = at
                while (start < end && text[start].isWhitespace()) start++
                val stop = trimmedEnd(start, end)
                if (stop > start) {
                    if (header) add(start, stop, TokenType.ATTRIBUTE)
                    else if (csvNumber.matches(text.substring(start, stop))) add(start, stop, TokenType.NUMBER)
                }
                at = end
            }
            if (at >= until) break
            if (text[at] == '\n') header = false
            at++
        }
    }

    fun yaml(from: Int, until: Int) {
        var lineStart = from
        while (lineStart < until) {
            val end = lineEnd(lineStart, until)
            var at = lineStart
            while (at < end && text[at] == ' ') at++
            while (at + 1 < end && text[at] == '-' && (text[at + 1] == ' ' || text[at + 1] == '\t')) {
                at += 2
                while (at < end && (text[at] == ' ' || text[at] == '\t')) at++
            }
            if (at < end && text[at] == '#') {
                add(at, trimmedEnd(at, end), TokenType.COMMENT)
            } else {
                val colon = yamlKeyEnd(at, end)
                if (colon > at) {
                    add(at, trimmedEnd(at, colon), TokenType.ATTRIBUTE)
                    at = colon + 1
                }
                yamlValue(at, end)
            }
            lineStart = end + 1
        }
    }

    private fun yamlKeyEnd(from: Int, until: Int): Int {
        if (from >= until || text[from] in "\"'{[#&*!|>%@`") return -1
        var at = from
        while (at < until) {
            val c = text[at]
            if (c == '#' && at > from && text[at - 1].isWhitespace()) return -1
            if (c == ':' && (at + 1 == until || text[at + 1].isWhitespace())) return at
            at++
        }
        return -1
    }

    private fun yamlValue(from: Int, until: Int) {
        var at = from
        while (at < until) {
            val c = text[at]
            at = when {
                c == '#' && (at == from || text[at - 1].isWhitespace()) -> { add(at, trimmedEnd(at, until), TokenType.COMMENT); until }
                c == '"' -> quoted(at, until, "\"", escapes = true, multiline = false).also { add(at, it, TokenType.STRING) }
                c == '\'' -> quoted(at, until, "'", escapes = false, multiline = false).also { add(at, it, TokenType.STRING) }
                c.isWhitespace() || c in ",[]{}" -> at + 1
                else -> {
                    var end = at
                    while (end < until && !text[end].isWhitespace() && text[end] !in ",[]{}") end++
                    val word = text.substring(at, end)
                    when {
                        word.lowercase() in Keywords.yamlWords -> add(at, end, TokenType.KEYWORD)
                        yamlNumber.matches(word) -> add(at, end, TokenType.NUMBER)
                    }
                    end
                }
            }
        }
    }
}
