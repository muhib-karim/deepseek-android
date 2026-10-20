package app.dsandroid.core.editor

import app.dsandroid.core.editor.TokenType.*
import kotlin.random.Random
import org.junit.Assert.*
import org.junit.Test

class SyntaxTokenizerTest {
    private fun spans(text: String, language: EditorLanguage): List<Pair<String, TokenType>> =
        SyntaxTokenizer.tokenize(text, language).map { text.substring(it.start, it.end) to it.type }

    private fun assertHas(text: String, language: EditorLanguage, vararg expected: Pair<String, TokenType>) {
        val actual = spans(text, language)
        expected.forEach { assertTrue("$language: missing $it in $actual", it in actual) }
    }

    @Test fun languagesComeFromFileNamesAndFenceTags() {
        assertEquals(EditorLanguage.KOTLIN, EditorLanguage.forFileName("Main.kt"))
        assertEquals(EditorLanguage.TYPESCRIPT, EditorLanguage.forFileName("src/app.TS"))
        assertEquals(EditorLanguage.YAML, EditorLanguage.forFileName("ci.yml"))
        assertEquals(EditorLanguage.XML, EditorLanguage.forFileName("chart.svg"))
        assertNull(EditorLanguage.forFileName("report.pdf"))
        assertNull(EditorLanguage.forFileName("Makefile"))
        assertEquals(EditorLanguage.PYTHON, EditorLanguage.forFence("python3"))
        assertEquals(EditorLanguage.SHELL, EditorLanguage.forFence("bash"))
        assertEquals(EditorLanguage.TEXT, EditorLanguage.forFence(""))
        assertNull(EditorLanguage.forFence("brainfuck"))
        assertEquals("snippet.py", EditorLanguage.PYTHON.fileName("snippet"))
        EditorLanguage.entries.forEach { assertEquals(it, EditorLanguage.forFileName("x." + it.extensions.first())) }
    }

    @Test fun cLikeFamilyKotlinJavaScriptTypeScript() {
        assertHas("val x = \"hi \\\"there\\\"\" // note\n/* block\ncomment */ fun f() = 0x1F + 42L", EditorLanguage.KOTLIN,
            "val" to KEYWORD, "\"hi \\\"there\\\"\"" to STRING, "// note" to COMMENT, "/* block\ncomment */" to COMMENT,
            "fun" to KEYWORD, "0x1F" to NUMBER, "42L" to NUMBER)
        assertHas("@Composable fun Ui() { val raw = \"\"\"a \"quoted\" b\"\"\" }", EditorLanguage.KOTLIN,
            "@Composable" to ATTRIBUTE, "\"\"\"a \"quoted\" b\"\"\"" to STRING)
        assertHas("const total = `sum ${'$'}{a}` + 'x'; // done\nlet n = 3.5e2;", EditorLanguage.JAVASCRIPT,
            "const" to KEYWORD, "`sum ${'$'}{a}`" to STRING, "'x'" to STRING, "// done" to COMMENT, "let" to KEYWORD, "3.5e2" to NUMBER)
        assertHas("interface Point { x: number } type P = readonly Point[]", EditorLanguage.TYPESCRIPT,
            "interface" to KEYWORD, "number" to KEYWORD, "type" to KEYWORD, "readonly" to KEYWORD)
        assertFalse(spans("valid = 1", EditorLanguage.KOTLIN).any { it.first == "val" })
    }

    @Test fun pythonFamily() {
        assertHas("@cache\ndef area(r):\n    \"\"\"Doc\n    string\"\"\"\n    return 3.14 * r ** 2  # pi\nprint(f'{area(2)}', rb\"raw\", None)",
            EditorLanguage.PYTHON, "@cache" to ATTRIBUTE, "def" to KEYWORD, "\"\"\"Doc\n    string\"\"\"" to STRING, "return" to KEYWORD,
            "3.14" to NUMBER, "2" to NUMBER, "# pi" to COMMENT, "f'{area(2)}'" to STRING, "rb\"raw\"" to STRING, "None" to KEYWORD)
        assertHas("x = 'unterminated\ny = 1", EditorLanguage.PYTHON, "'unterminated" to STRING, "1" to NUMBER)
    }

    @Test fun shellFamily() {
        assertHas("#!/bin/sh\nif [ -n \"${'$'}HOME\" ]; then echo 'hi' # greet\nfi\nexport N=10\nurl=a#b", EditorLanguage.SHELL,
            "#!/bin/sh" to COMMENT, "if" to KEYWORD, "\"${'$'}HOME\"" to STRING, "then" to KEYWORD, "'hi'" to STRING,
            "# greet" to COMMENT, "fi" to KEYWORD, "export" to KEYWORD, "10" to NUMBER)
        assertFalse(spans("url=a#b", EditorLanguage.SHELL).any { it.second == COMMENT })
        assertHas("echo ${'$'}{NAME} ${'$'}PATH", EditorLanguage.SHELL, "${'$'}{NAME}" to ATTRIBUTE, "${'$'}PATH" to ATTRIBUTE)
    }

    @Test fun sqlFamilyIsCaseInsensitive() {
        assertHas("select name, 'it''s' as q from users -- all\nWHERE id = 42 /* x */", EditorLanguage.SQL,
            "select" to KEYWORD, "'it''s'" to STRING, "as" to KEYWORD, "from" to KEYWORD, "-- all" to COMMENT,
            "WHERE" to KEYWORD, "42" to NUMBER, "/* x */" to COMMENT)
    }

    @Test fun markupFamilyWithEmbeddedScriptAndStyle() {
        val html = "<!doctype html>\n<!-- top -->\n<a href=\"/x\" data-n='1' hidden>Link</a>\n<script>const n = 1; // c\n</script>\n<style>p { color: red; }</style>"
        assertHas(html, EditorLanguage.HTML, "<!doctype html>" to KEYWORD, "<!-- top -->" to COMMENT, "<a" to TAG, "href" to ATTRIBUTE,
            "\"/x\"" to STRING, "data-n" to ATTRIBUTE, "'1'" to STRING, "hidden" to ATTRIBUTE, ">" to TAG, "</a" to TAG,
            "const" to KEYWORD, "// c" to COMMENT, "color" to ATTRIBUTE, "<script" to TAG, "</style" to TAG)
        assertFalse(spans(html, EditorLanguage.HTML).any { it.first == "Link" })
        assertHas("<?xml version=\"1.0\"?><svg viewBox=\"0 0 4 4\"/>", EditorLanguage.XML,
            "<?xml version=\"1.0\"?>" to KEYWORD, "<svg" to TAG, "viewBox" to ATTRIBUTE, "\"0 0 4 4\"" to STRING, "/>" to TAG)
    }

    @Test fun cssFamily() {
        assertHas("/* theme */\n@media (max-width: 600px) {\n  .card > h1, #main { margin: 0 auto !important; color: #fff; }\n}", EditorLanguage.CSS,
            "/* theme */" to COMMENT, "@media" to KEYWORD, "600px" to NUMBER, ".card" to TAG, "h1" to TAG, "#main" to TAG,
            "margin" to ATTRIBUTE, "0" to NUMBER, "!important" to KEYWORD, "color" to ATTRIBUTE, "#fff" to NUMBER)
    }

    @Test fun jsonFamilySeparatesKeysFromStringValues() {
        assertHas("{\"name\": \"Ada\", \"age\": -36.5, \"ok\": true, \"none\": null}", EditorLanguage.JSON,
            "\"name\"" to ATTRIBUTE, "\"Ada\"" to STRING, "\"age\"" to ATTRIBUTE, "-36.5" to NUMBER, "true" to KEYWORD, "null" to KEYWORD)
    }

    @Test fun markdownFamily() {
        val text = "# Title\n\nSome **bold** and `code` with a [link](https://example.com).\n\n```kotlin\nval x = 1\n```\n> quote\n- item"
        assertHas(text, EditorLanguage.MARKDOWN, "# Title" to HEADING, "**bold**" to KEYWORD, "`code`" to STRING,
            "(https://example.com)" to ATTRIBUTE, "```kotlin\nval x = 1\n```" to STRING, "> quote" to COMMENT, "-" to KEYWORD)
    }

    @Test fun csvFamily() {
        assertHas("name,score,note\n\"Lovelace, Ada\",36.5,\"said \"\"hi\"\"\"\nBob,7,x", EditorLanguage.CSV,
            "name" to ATTRIBUTE, "score" to ATTRIBUTE, "\"Lovelace, Ada\"" to STRING, "36.5" to NUMBER, "\"said \"\"hi\"\"\"" to STRING, "7" to NUMBER)
        assertFalse(spans("Bob,7,x", EditorLanguage.CSV).any { it.first == "x" && it.second == STRING })
    }

    @Test fun yamlFamily() {
        assertHas("# config\nname: \"demo\"\nversion: 2\nsteps:\n  - run: echo hi # inline\n    enabled: true\nempty: ~", EditorLanguage.YAML,
            "# config" to COMMENT, "name" to ATTRIBUTE, "\"demo\"" to STRING, "version" to ATTRIBUTE, "2" to NUMBER,
            "run" to ATTRIBUTE, "# inline" to COMMENT, "enabled" to ATTRIBUTE, "true" to KEYWORD, "~" to KEYWORD)
    }

    @Test fun plainTextHasNoTokensAndHugeTextIsSkipped() {
        assertTrue(SyntaxTokenizer.tokenize("just words 42 \"quoted\"", EditorLanguage.TEXT).isEmpty())
        assertTrue(SyntaxTokenizer.tokenize("val x = 1\n".repeat(SyntaxTokenizer.MAX_CHARS / 10 + 1), EditorLanguage.KOTLIN).isEmpty())
    }

    @Test fun tokensAreOrderedDisjointAndInBoundsForRandomInput() {
        val random = Random(70)
        val alphabet = "ab1 _\"'`#/*-<>=!:;{}()[]\n\\\$@.,~|&%?"
        repeat(400) {
            val text = String(CharArray(random.nextInt(0, 120)) { alphabet[random.nextInt(alphabet.length)] })
            EditorLanguage.entries.forEach { language ->
                val tokens = SyntaxTokenizer.tokenize(text, language)
                var last = 0
                tokens.forEach { token ->
                    assertTrue("$language $token in ${text.length}", token.start >= last && token.end > token.start && token.end <= text.length)
                    last = token.end
                }
            }
        }
    }
}
