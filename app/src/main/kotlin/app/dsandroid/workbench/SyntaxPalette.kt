package app.dsandroid.workbench

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import app.dsandroid.core.editor.TokenType

/** Editor colors; every token color keeps at least 4.5:1 contrast on its background (SyntaxPaletteTest). */
data class SyntaxPalette(
    val background: Color, val plain: Color, val gutter: Color, val keyword: Color, val string: Color, val comment: Color,
    val number: Color, val tag: Color, val attribute: Color, val match: Color, val currentMatch: Color,
) {
    fun color(type: TokenType): Color = when (type) {
        TokenType.KEYWORD, TokenType.HEADING -> keyword
        TokenType.STRING -> string
        TokenType.COMMENT -> comment
        TokenType.NUMBER -> number
        TokenType.TAG -> tag
        TokenType.ATTRIBUTE -> attribute
    }

    fun style(type: TokenType): SpanStyle =
        SpanStyle(color = color(type), fontWeight = if (type == TokenType.HEADING) FontWeight.Bold else null)

    companion object {
        val Light = SyntaxPalette(
            background = Color(0xFFFAFAFB), plain = Color(0xFF1A1A1A), gutter = Color(0xFF6B6F76), keyword = Color(0xFF2B4FC7),
            string = Color(0xFF0F7B3D), comment = Color(0xFF6B6F76), number = Color(0xFFB3261E), tag = Color(0xFF7A3E9D),
            attribute = Color(0xFF8A5300), match = Color(0x66FFD54F), currentMatch = Color(0xAAFFB300),
        )
        val Dark = SyntaxPalette(
            background = Color(0xFF1C1D21), plain = Color(0xFFEDEEF2), gutter = Color(0xFF9A9EA6), keyword = Color(0xFF8FAEFF),
            string = Color(0xFF7FD08F), comment = Color(0xFF9A9EA6), number = Color(0xFFFF9B8A), tag = Color(0xFFD4A5F5),
            attribute = Color(0xFFF0B860), match = Color(0x66806000), currentMatch = Color(0xAAB07800),
        )
    }
}
