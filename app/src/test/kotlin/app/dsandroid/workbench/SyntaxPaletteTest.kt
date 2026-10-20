package app.dsandroid.workbench

import androidx.compose.ui.graphics.Color
import app.dsandroid.core.editor.TokenType
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

class SyntaxPaletteTest {
    @Test fun everyTokenColorMeetsNormalTextContrastInLightAndDark() {
        listOf(SyntaxPalette.Light, SyntaxPalette.Dark).forEach { palette ->
            (TokenType.entries.map { palette.color(it) } + palette.plain + palette.gutter).forEach { color ->
                val a = luminance(color); val b = luminance(palette.background)
                val ratio = (maxOf(a, b) + 0.05) / (minOf(a, b) + 0.05)
                assertTrue("Contrast $ratio for $color on ${palette.background}", ratio >= 4.5)
            }
        }
    }

    private fun luminance(color: Color): Double {
        fun linear(v: Float) = if (v <= 0.04045f) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
        return 0.2126 * linear(color.red) + 0.7152 * linear(color.green) + 0.0722 * linear(color.blue)
    }
}
