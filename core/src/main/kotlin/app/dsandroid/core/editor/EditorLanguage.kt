package app.dsandroid.core.editor

/** Tokenizer families; languages in one family share comment, string and number rules. */
enum class LanguageFamily { C_LIKE, PYTHON, SHELL, SQL, MARKUP, CSS, JSON, MARKDOWN, CSV, YAML, PLAIN }

/** The text formats the in-app editor opens, by file extension (first one is the default for new files). */
enum class EditorLanguage(val label: String, val extensions: List<String>, val family: LanguageFamily, val mimeType: String) {
    KOTLIN("Kotlin", listOf("kt", "kts"), LanguageFamily.C_LIKE, "text/x-kotlin"),
    PYTHON("Python", listOf("py"), LanguageFamily.PYTHON, "text/x-python"),
    JAVASCRIPT("JavaScript", listOf("js", "mjs", "cjs"), LanguageFamily.C_LIKE, "text/javascript"),
    TYPESCRIPT("TypeScript", listOf("ts"), LanguageFamily.C_LIKE, "text/x-typescript"),
    HTML("HTML", listOf("html", "htm"), LanguageFamily.MARKUP, "text/html"),
    XML("XML", listOf("xml", "svg"), LanguageFamily.MARKUP, "text/xml"),
    CSS("CSS", listOf("css"), LanguageFamily.CSS, "text/css"),
    JSON("JSON", listOf("json"), LanguageFamily.JSON, "application/json"),
    MARKDOWN("Markdown", listOf("md", "markdown"), LanguageFamily.MARKDOWN, "text/markdown"),
    CSV("CSV", listOf("csv"), LanguageFamily.CSV, "text/csv"),
    SHELL("Shell", listOf("sh", "bash"), LanguageFamily.SHELL, "application/x-sh"),
    SQL("SQL", listOf("sql"), LanguageFamily.SQL, "application/sql"),
    YAML("YAML", listOf("yaml", "yml"), LanguageFamily.YAML, "application/yaml"),
    TEXT("Text", listOf("txt", "text", "log"), LanguageFamily.PLAIN, "text/plain");

    fun fileName(stem: String): String = "$stem.${extensions.first()}"

    companion object {
        /** Null when the file is not one of the editable text formats. */
        fun forFileName(name: String): EditorLanguage? {
            val base = name.substringAfterLast('/')
            if (!base.contains('.')) return null
            val extension = base.substringAfterLast('.').lowercase()
            return entries.firstOrNull { extension in it.extensions }
        }

        /** A Markdown fence's info string; an empty tag is plain text. */
        fun forFence(tag: String): EditorLanguage? = when (tag.trim().substringBefore(' ').lowercase()) {
            "kotlin", "kt", "kts" -> KOTLIN
            "python", "py", "python3", "py3" -> PYTHON
            "javascript", "js", "jsx", "mjs", "node" -> JAVASCRIPT
            "typescript", "ts", "tsx" -> TYPESCRIPT
            "html", "htm", "xhtml" -> HTML
            "xml", "svg" -> XML
            "css" -> CSS
            "json", "jsonc" -> JSON
            "markdown", "md" -> MARKDOWN
            "csv" -> CSV
            "sh", "bash", "shell", "zsh" -> SHELL
            "sql", "sqlite", "postgresql", "mysql" -> SQL
            "yaml", "yml" -> YAML
            "", "text", "txt", "plain", "plaintext" -> TEXT
            else -> null
        }
    }
}
