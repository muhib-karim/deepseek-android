package app.dsandroid.ui

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

class ContrastTest {
    @Test fun darkTextAndAccentPairsMeetNormalTextContrast() {
        val colors = DarkChatColors
        listOf(colors.onSurface to colors.surface, colors.onBackground to colors.background,
            colors.onSurfaceVariant to colors.surfaceVariant, colors.onPrimary to colors.primary,
            colors.onPrimaryContainer to colors.primaryContainer, Color.White to AccentBlue).forEach { (text, background) ->
            val a = luminance(text); val b = luminance(background)
            assertTrue("Text contrast must be at least 4.5:1", (maxOf(a, b) + 0.05) / (minOf(a, b) + 0.05) >= 4.5)
        }
    }
    private fun luminance(color: Color): Double {
        fun linear(v: Float) = if (v <= 0.04045f) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
        return 0.2126 * linear(color.red) + 0.7152 * linear(color.green) + 0.0722 * linear(color.blue)
    }
}
